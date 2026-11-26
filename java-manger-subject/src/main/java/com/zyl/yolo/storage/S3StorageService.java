package com.zyl.yolo.storage;

import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * S3（RustFS / MinIO）存储实现
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "yolo.storage", name = "type", havingValue = "s3")
public class S3StorageService implements StorageService {

    private final S3Client s3Client;

    public S3StorageService(S3Client s3Client) {
        this.s3Client = s3Client;
        initBuckets();
    }

    private void initBuckets() {
        ensureBucket(BucketUtils.DATASETS);
        ensureBucket(BucketUtils.MODELS);
        ensureBucket(BucketUtils.TESTS);
        log.info("S3 存储桶初始化完成: {}, {}, {}", BucketUtils.DATASETS, BucketUtils.MODELS, BucketUtils.TESTS);
    }

    private void ensureBucket(String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("创建 S3 存储桶: {}", bucket);
            } catch (S3Exception createEx) {
                log.warn("创建存储桶 {} 失败（可能已存在）: {}", bucket, createEx.getMessage());
            }
        } catch (S3Exception e) {
            log.warn("检查存储桶 {} 异常: {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void upload(String bucket, String key, byte[] content, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "S3 上传失败: " + e.getMessage());
        }
    }

    @Override
    public void upload(String bucket, String key, InputStream inputStream, String contentType) {
        try {
            byte[] content = inputStream.readAllBytes();
            upload(bucket, key, content, contentType);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "S3 上传失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String bucket, String key) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (S3Exception e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "S3 下载失败: " + e.getMessage());
        }
    }

    @Override
    public String downloadToFile(String bucket, String key, String localPath) {
        try {
            java.nio.file.Path target = Paths.get(localPath).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            try (InputStream in = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (S3Exception | IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "S3 下载失败: " + e.getMessage());
        }
    }
    @Override
    public void delete(String bucket, String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            log.warn("S3 删除失败: bucket={}, key={}", bucket, key, e);
        }
    }

    @Override
    public List<String> listKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            var builder = ListObjectsV2Request.builder().bucket(bucket).maxKeys(1000);
            if (prefix != null && !prefix.isEmpty()) {
                builder = builder.prefix(prefix);
            }
            if (continuationToken != null) {
                builder = builder.continuationToken(continuationToken);
            }
            var response = s3Client.listObjectsV2(builder.build());
            keys.addAll(response.contents().stream().map(S3Object::key).collect(Collectors.toList()));
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }

    @Override
    public String getPublicUrl(String bucket, String key) {
        // 统一走后端代理接口，避免前端需要直连对象存储或要求公开读权限
        return "/api/storage/" + bucket + "/" + key;
    }
}