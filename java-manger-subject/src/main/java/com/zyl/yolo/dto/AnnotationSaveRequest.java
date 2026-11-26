package com.zyl.yolo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存标注请求
 */
@Data
public class AnnotationSaveRequest {

    @NotNull(message = "图片 ID 不能为空")
    private Long imageId;

    @NotNull(message = "标签 ID 不能为空")
    private Long labelId;

    @NotNull(message = "归一化中心 X 不能为空")
    @DecimalMin(value = "0.0", message = "xCenter 范围错误")
    @DecimalMax(value = "1.0", message = "xCenter 范围错误")
    @JsonProperty("xCenter")
    private BigDecimal xCenter;

    @NotNull(message = "归一化中心 Y 不能为空")
    @DecimalMin(value = "0.0", message = "yCenter 范围错误")
    @DecimalMax(value = "1.0", message = "yCenter 范围错误")
    @JsonProperty("yCenter")
    private BigDecimal yCenter;

    @NotNull(message = "归一化宽度不能为空")
    @DecimalMin(value = "0.0", message = "width 范围错误")
    @DecimalMax(value = "1.0", message = "width 范围错误")
    private BigDecimal width;

    @NotNull(message = "归一化高度不能为空")
    @DecimalMin(value = "0.0", message = "height 范围错误")
    @DecimalMax(value = "1.0", message = "height 范围错误")
    private BigDecimal height;
}