package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 训练指标点
 */
@Data
public class MetricPoint {
    private Integer epoch;
    private java.math.BigDecimal boxLoss;
    private java.math.BigDecimal clsLoss;
    private java.math.BigDecimal map50;
    private java.math.BigDecimal map5095;
}