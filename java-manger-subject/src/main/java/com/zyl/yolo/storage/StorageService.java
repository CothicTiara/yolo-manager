package com.zyl.yolo.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 文件存储抽象接口
 * <p>支持本地磁盘（开发调试）与 S3 兼容对象存储（RustFS / MinIO）</p>
 */
public interface StorageService {

    /**
     * 文件是否存在
     */
    boolean exists(String bucket, String key);

    /**
     * 上传文件（字节）
     */
    void upload(String bucket, String key, byte[] content, String contentType);

    /**
     * 上传文件（流）
     */
    void upload(String bucket, String key, InputStream inputStream, String contentType);

    /**
     * 下载文件为字节数组
     */
    byte[] download(String bucket, String key);

    /**
     * 下载文件到本地临时文件，返回文件路径
     */
    String downloadToFile(String bucket, String key, String localPath);

    /**
     * 删除对象
     */
    void delete(String bucket, String key);

    /**
     * 列出目录下对象 key
     */
    List<String> listKeys(String bucket, String prefix);

    /**
     * 生成可访问 URL（本地模式为相对 /storage 路径，S3 模式为相对 /api/storage 代理路径）
     */
    String getPublicUrl(String bucket, String key);
}