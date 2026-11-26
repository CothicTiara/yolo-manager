package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 官网（Ultralytics GitHub Release Assets）返回的可选基础模型条目。
 * 前端「同步官网最新」弹窗中展示；用户勾选后调用下载接口。
 */
@Data
public class OfficialBaseModel {

    /** 平台建议名：如 yolov26n（v8 家族官方名带 v，v11/v26 家族无 v 时自动补 v） */
    private String modelName;

    /** 家族：v8 / v11 / v26 */
    private String family;

    /** 尺度：n / s / m / l / x */
    private String scale;

    /** 官方 .pt 文件名（yolov8n.pt / yolo11n.pt / yolo26n.pt） */
    private String officialFile;

    /** GitHub Release 原始直链 */
    private String downloadUrl;

    /**
     * 官网目录中当前是否已在本地/数据库存在：
     * true=DB 已有该 modelName；false=全新，将作为 MISSING 写入。
     */
    private Boolean known;

    /** 该模型在 base_model 表中的当前状态（known=false 时为 null） */
    private String currentStatus;
}
