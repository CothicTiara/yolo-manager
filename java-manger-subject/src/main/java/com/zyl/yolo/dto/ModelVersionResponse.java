package com.zyl.yolo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zyl.yolo.entity.ModelVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型版本响应
 */
@Data
@Schema(description = "模型版本响应")
public class ModelVersionResponse {

    @Schema(description = "模型版本 ID")
    private Long id;

    @Schema(description = "模型唯一标识（32位UUID），对外推理接口以此定位模型")
    private String uuid;

    @Schema(description = "版本号，如 v1.0.0")
    private String version;

    @Schema(description = "模型名称（可编辑）；注册时自动生成默认名")
    private String name;

    @Schema(description = "系列根模型 ID；同系列（初次训练+后续续训/同名上传）共享，前端按此折叠展示")
    private Long seriesId;

    @Schema(description = "训练所用基底模型：显示续训链根模型的架构名（如 yolo11n），根为手动上传时显示「手动上传」")
    private String baseModel;

    @Schema(description = "使用的训练集名称列表（沿续训链回溯，可多个）；手动上传为空列表")
    private List<String> datasets;

    @Schema(description = "来源训练任务 ID；手动上传的模型为 null")
    private Long taskId;

    @Schema(description = "best.pt 存储路径")
    private String bestPtKey;

    @Schema(description = "last.pt 存储路径")
    private String lastPtKey;

    @Schema(description = "mAP@0.50")
    private BigDecimal map50;

    @Schema(description = "mAP@0.50:0.95")
    @JsonProperty("map5095")
    private BigDecimal map5095;

    @Schema(description = "是否当前上线版本：1=上线（对外接口默认模型），0=未上线")
    private Integer isActive;

    @Schema(description = "最后训练完成时间")
    private LocalDateTime lastTrainedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static ModelVersionResponse from(ModelVersion mv) {
        ModelVersionResponse resp = new ModelVersionResponse();
        resp.setId(mv.getId());
        resp.setUuid(mv.getUuid());
        resp.setVersion(mv.getVersion());
        resp.setName(mv.getName());
        resp.setSeriesId(mv.getSeriesId());
        resp.setTaskId(mv.getTaskId());
        resp.setBestPtKey(mv.getBestPtKey());
        resp.setLastPtKey(mv.getLastPtKey());
        resp.setMap50(mv.getMap50());
        resp.setMap5095(mv.getMap5095());
        resp.setIsActive(mv.getIsActive());
        resp.setLastTrainedAt(mv.getLastTrainedAt());
        resp.setCreatedAt(mv.getCreatedAt());
        return resp;
    }
}