package com.zyl.yolo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 创建训练任务请求
 */
@Data
public class TrainTaskCreateRequest {

    @NotNull(message = "数据集 ID 不能为空")
    private Long datasetId;

    /** type=BASE 时必填；type=TRAINED 时可为空（后端会回填 base 名称） */
    private String modelBase;

    /** BASE / TRAINED，默认 BASE */
    @Pattern(regexp = "BASE|TRAINED", message = "baseModelType 仅支持 BASE / TRAINED")
    private String baseModelType = "BASE";

    /** type=TRAINED 时必填，源 model_version.id */
    private Long baseVersionId;

    /** type=TRAINED 时的版本步长：MINOR=+0.1（默认），MAJOR=+1.0 */
    @Pattern(regexp = "MINOR|MAJOR", message = "versionBump 仅支持 MINOR / MAJOR")
    private String versionBump = "MINOR";

    @NotNull(message = "训练轮数不能为空")
    @Min(value = 1, message = "epochs 最小为 1")
    @Max(value = 1000, message = "epochs 最大为 1000")
    private Integer epochs;

    @NotNull(message = "批次大小不能为空")
    @Min(value = 1, message = "batchSize 最小为 1")
    @Max(value = 256, message = "batchSize 最大为 256")
    private Integer batchSize;

    @NotNull(message = "输入尺寸不能为空")
    @Min(value = 32, message = "imgsz 最小为 32")
    @Max(value = 4096, message = "imgsz 最大为 4096")
    private Integer imgsz;
}