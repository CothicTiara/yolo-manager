package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 导出标注响应
 */
@Data
public class ExportResponse {

    private String exportPath;
    private Integer labelCount;
    private Integer imageCount;
}