package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基础预训练权重目录条目（对应表 base_model）。
 * <p>实际 .pt 文件存放于 Python 训练端 (172.16.5.124) 的 base_models_dir，
 * 本表仅保存目录元信息与同步状态，用于训练表单下拉与「点击下载到服务器」流程。</p>
 */
@Data
@TableName("base_model")
public class BaseModel {

    public static final String STATUS_MISSING = "MISSING";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_DOWNLOADED = "DOWNLOADED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台模型名：yolov8n / yolov11m / yolov26x ...（训练任务 model_base 的取值域） */
    private String modelName;

    /** 模型家族：v8 / v11 / v26 ... */
    private String family;

    /** 尺度：n / s / m / l / x */
    private String scale;

    /** Ultralytics 官方 .pt 文件名：yolov8n.pt / yolo11n.pt / yolo26n.pt ... */
    private String officialFile;

    /** 官方下载直链（GitHub Release 原始 URL） */
    private String downloadUrl;

    /** 本地文件字节大小（同步后回填；未知为 0） */
    private Long sizeBytes;

    /** MISSING / DOWNLOADING / DOWNLOADED / FAILED */
    private String status;

    /** 上一次下载失败原因（成功后清空） */
    private String errorMsg;

    /** 是否在训练表单中可见（0=下架，1=启用） */
    private Integer enabled;

    /** Python 端 (.124) 上对应的绝对路径 */
    private String localPath;

    /** 最后一次状态同步时间 */
    private LocalDateTime lastSyncAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
