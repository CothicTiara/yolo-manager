package com.zyl.yolo.dto;

import com.zyl.yolo.entity.Dataset;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据集响应
 */
@Data
public class DatasetResponse {

    private Long id;
    private String name;
    private String description;
    private BigDecimal trainRatio;
    private String status;
    private LocalDateTime createdAt;
    private Long imageCount;
    /** 已有标注的图片数（至少一条标注即计入）；未标注数 = imageCount - annotatedCount */
    private Long annotatedCount;
    /** 当前占用该数据集的后台任务类型：IMPORT / EXPORT / UPLOAD；为空表示空闲 */
    private String jobKind;

    public static DatasetResponse from(Dataset dataset) {
        DatasetResponse resp = new DatasetResponse();
        resp.setId(dataset.getId());
        resp.setName(dataset.getName());
        resp.setDescription(dataset.getDescription());
        resp.setTrainRatio(dataset.getTrainRatio());
        resp.setStatus(dataset.getStatus());
        resp.setCreatedAt(dataset.getCreatedAt());
        return resp;
    }
}