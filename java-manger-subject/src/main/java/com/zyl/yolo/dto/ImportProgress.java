package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 数据集导入进度（异步导入的状态快照，供前端轮询）
 */
@Data
public class ImportProgress {

    private Long datasetId;

    /** NONE / PENDING / PROCESSING / DONE / FAILED */
    private String status;

    /** 实际解析格式：COCO / YOLO / IMAGES */
    private String format;

    /** 待处理图片总数 */
    private Integer totalImages;

    /** 已处理图片数 */
    private Integer processedImages;

    /** 成功导入的图片数 */
    private Integer imageCount;

    /** 成功导入的标注数 */
    private Integer annotationCount;

    /** 涉及/创建的标签数 */
    private Integer labelCount;

    /** 被跳过的条目数 */
    private Integer skippedCount;

    private String message;

    private String error;

    public static ImportProgress idle(Long datasetId) {
        ImportProgress p = new ImportProgress();
        p.setDatasetId(datasetId);
        p.setStatus("NONE");
        return p;
    }
}
