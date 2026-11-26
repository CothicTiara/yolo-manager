package com.zyl.yolo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 标签创建请求
 */
@Data
public class LabelCreateRequest {

    @NotBlank(message = "标签名称不能为空")
    private String name;

    /** YOLO 类别编号 */
    private Integer code;

    private String color;
}