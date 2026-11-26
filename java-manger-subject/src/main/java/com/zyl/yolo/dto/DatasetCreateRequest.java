package com.zyl.yolo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建数据集请求
 */
@Data
public class DatasetCreateRequest {

    @NotBlank(message = "数据集名称不能为空")
    @Size(max = 100, message = "数据集名称长度不能超过 100")
    private String name;

    @Size(max = 500, message = "描述长度不能超过 500")
    private String description;

    /** 训练集比例，默认 0.8 */
    private java.math.BigDecimal trainRatio;
}