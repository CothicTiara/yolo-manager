package com.zyl.yolo.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据集导入结果
 */
@Data
public class DatasetImportResponse {

    private Long datasetId;

    /** 实际导入格式：COCO / IMAGES */
    private String format;

    /** 成功导入的图片数 */
    private Integer imageCount;

    /** 成功导入的标注数（COCO 模式） */
    private Integer annotationCount;

    /** 新建/复用的标签数 */
    private Integer labelCount;

    /** 被跳过的条目说明（如缺少图片、非法坐标等） */
    private List<String> skipped;

    private String message;
}
