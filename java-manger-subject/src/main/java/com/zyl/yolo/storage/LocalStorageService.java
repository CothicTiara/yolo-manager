package com.zyl.yolo.storage;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地磁盘存储实现（开发调试模式）
 * <p>目录结构：{localRoot}/{bucket}/{key}，通过 /storage/** 静态映射对外访问</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "yolo.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(@Value("${yolo.storage.local-root:./data/storage}") String localRoot) {
        this.root = Paths.get(localRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("初始化本地存储目录失败: " + this.root, e);
        }
        log.info("本地存储根目录: {}", this.root);
    }

    private Path resolve(String bucket, String key) {
        // 防止路径穿越
        Path base = root.resolve(bucket).normalize();
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "非法的存储路径: " + key);
        }
        return target;
    }

    @Override
    public boolean exists(String bucket, String key) {
        return Files.exists(resolve(bucket, key));
    }

    @Override
    public void upload(String bucket, String key, byte[] content, String contentType) {
        upload(bucket, key, new ByteArrayInputStream(content), contentType);
    }

    @Override
    public void upload(String bucket, String key, InputStream inputStream, String contentType) {
        try {
            Path target = resolve(bucket, key);
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String bucket, String key) {
        try {
            return Files.readAllBytes(resolve(bucket, key));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public String downloadToFile(String bucket, String key, String localPath) {
        try {
            Path target = Paths.get(localPath).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Files.copy(resolve(bucket, key), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            Files.deleteIfExists(resolve(bucket, key));
        } catch (IOException e) {
            log.warn("删除本地文件失败: bucket={}, key={}", bucket, key, e);
        }
    }

    @Override
    public List<String> listKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        Path base = root.resolve(bucket).normalize();
        Path search = base.resolve(prefix == null ? "" : prefix).normalize();
        if (!Files.exists(search)) {
            return keys;
        }
        try (Stream<Path> stream = Files.walk(search)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String key = BucketUtils.join(prefix, base.relativize(p).toString());
                keys.add(key);
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "列出文件失败: " + e.getMessage());
        }
        return keys;
    }

    @Override
    public String getPublicUrl(String bucket, String key) {
        return "/storage/" + bucket + "/" + key;
    }

    /**
     * 生成带扩展名的唯一文件名
     */
    public static String uniqueFileName(String extension) {
        return IdUtil.fastSimpleUUID() + "." + (extension == null ? "jpg" : extension);
    }
}