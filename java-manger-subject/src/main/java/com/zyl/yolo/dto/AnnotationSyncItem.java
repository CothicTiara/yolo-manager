package com.zyl.yolo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 标注批量同步项：id 为 null 表示新增，列表为某图片最终期望保留的全部标注。
 */
@Data
public class AnnotationSyncItem {

    /** 已有标注 ID（null = 新增） */
    private Long id;

    @NotNull(message = "标签 ID 不能为空")
    private Long labelId;

    @NotNull
    @DecimalMin("0.0") @DecimalMax("1.0")
    @JsonProperty("xCenter")
    private BigDecimal xCenter;

    @NotNull
    @DecimalMin("0.0") @DecimalMax("1.0")
    @JsonProperty("yCenter")
    private BigDecimal yCenter;

    @NotNull
    @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal width;

    @NotNull
    @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal height;
}
