package com.zyl.yolo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 标注响应
 */
@Data
public class AnnotationResponse {

    private Long id;
    private Long imageId;
    private Long labelId;
    private String labelName;
    private Integer labelCode;
    private String labelColor;

    @JsonProperty("xCenter")
    private BigDecimal xCenter;

    @JsonProperty("yCenter")
    private BigDecimal yCenter;

    private BigDecimal width;
    private BigDecimal height;
}