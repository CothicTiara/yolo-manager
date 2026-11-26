package com.zyl.yolo.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基础模型目录条目：返回给前端「基础模型」管理页与训练表单下拉使用。
 */
@Data
public class BaseModelInfo {

    private Long id;

    /** 平台模型名（训练 model_base 取值） */
    private String modelName;

    /** 家族：v8 / v11 / v26 */
    private String family;

    /** 尺度：n / s / m / l / x */
    private String scale;

    /** Ultralytics 官方 .pt 文件名（下载源） */
    private String officialFile;

    /** 官方直链（GitHub Release） */
    private String downloadUrl;

    /** 本地（.124）文件大小；未下载为 0 */
    private Long sizeBytes;

    /** MISSING / DOWNLOADING / DOWNLOADED / FAILED */
    private String status;

    /** 上次下载失败原因 */
    private String errorMsg;

    /** 是否在训练表单可选 */
    private Boolean enabled;

    /** .124 上的绝对路径（同步后写入） */
    private String localPath;

    private LocalDateTime lastSyncAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
