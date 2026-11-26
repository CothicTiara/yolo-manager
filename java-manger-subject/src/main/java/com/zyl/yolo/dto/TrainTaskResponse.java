package com.zyl.yolo.dto;

import com.zyl.yolo.entity.TrainTask;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 训练任务响应
 */
@Data
public class TrainTaskResponse {

    private Long id;
    private Long datasetId;
    private String datasetName;
    private String modelBase;
    private String baseModelType;
    private Long baseVersionId;
    private String baseVersionName;
    private String versionBump;
    private String expectedVersion;
    private Integer epochs;
    private Integer batchSize;
    private Integer imgsz;
    private String status;
    private Integer progress;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public static TrainTaskResponse from(TrainTask task) {
        TrainTaskResponse resp = new TrainTaskResponse();
        resp.setId(task.getId());
        resp.setDatasetId(task.getDatasetId());
        resp.setModelBase(task.getModelBase());
        resp.setBaseModelType(task.getBaseModelType());
        resp.setBaseVersionId(task.getBaseVersionId());
        resp.setVersionBump(task.getVersionBump());
        resp.setExpectedVersion(task.getExpectedVersion());
        resp.setEpochs(task.getEpochs());
        resp.setBatchSize(task.getBatchSize());
        resp.setImgsz(task.getImgsz());
        resp.setStatus(task.getStatus());
        resp.setProgress(task.getProgress());
        resp.setErrorMsg(task.getErrorMsg());
        resp.setStartedAt(task.getStartedAt());
        resp.setFinishedAt(task.getFinishedAt());
        resp.setCreatedAt(task.getCreatedAt());
        return resp;
    }
}