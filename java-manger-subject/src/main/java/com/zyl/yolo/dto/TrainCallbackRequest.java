package com.zyl.yolo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练回调请求（FastAPI 训练完成/失败时回调 Spring Boot）
 */
@Data
public class TrainCallbackRequest {

    private Long taskId;
    private String status;
    private String version;
    private String bestPtKey;
    private String lastPtKey;
    private String resultsCsvKey;
    private String argsYamlKey;
    private BigDecimal map50;
    private BigDecimal map5095;
    private String errorMsg;
    private Integer progress;
}