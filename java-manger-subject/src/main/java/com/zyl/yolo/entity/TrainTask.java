package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 训练任务
 */
@Data
@TableName("train_task")
public class TrainTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    /** yolov8n / yolov8s / ... */
    private String modelBase;

    /** BASE=基础模型, TRAINED=已训练模型继续训练 */
    private String baseModelType;

    /** 当 baseModelType=TRAINED 时关联的源 model_version.id */
    private Long baseVersionId;

    /** MINOR=+0.1, MAJOR=+1.0 */
    private String versionBump;

    /** 本次训练成功后预期注册的版本号 */
    private String expectedVersion;

    private Integer epochs;

    private Integer batchSize;

    private Integer imgsz;

    /** CREATED / QUEUED / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 当前 epoch */
    private Integer progress;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}