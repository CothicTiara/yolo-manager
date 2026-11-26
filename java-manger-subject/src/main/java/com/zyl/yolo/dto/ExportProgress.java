package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 数据集导出进度（异步导出的状态快照，供前端轮询）
 */
@Data
public class ExportProgress {

    private Long datasetId;

    /** NONE / PENDING / PROCESSING / DONE / FAILED */
    private String status;

    /** 待处理图片总数 */
    private Integer totalImages;

    /** 已处理图片数 */
    private Integer processedImages;

    /** 实际写出标注文件的图片数 */
    private Integer exportedImages;

    /** 导出的标注条数 */
    private Integer labelCount;

    private String message;

    private String error;

    public static ExportProgress idle(Long datasetId) {
        ExportProgress p = new ExportProgress();
        p.setDatasetId(datasetId);
        p.setStatus("NONE");
        p.setTotalImages(0);
        p.setProcessedImages(0);
        p.setExportedImages(0);
        p.setLabelCount(0);
        return p;
    }
}
