package com.zyl.yolo.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 训练任务表单中可选的"模型"项：
 *   type=BASE    -> value 形如 yolov8n，使用预训练权重从零训练
 *   type=TRAINED -> value 为 model_version.id，使用其 last.pt 作为初始权重续训
 */
@Data
public class BaseModelOption {

    /** BASE / TRAINED */
    private String type;

    /** 选项唯一值：BASE 时为 modelBase 名称；TRAINED 时为 model_version.id 字符串 */
    private String value;

    /** 显示文本 */
    private String label;

    /** BASE 时为基础模型名；TRAINED 时同样保留 base 名称（如 yolov8n） */
    private String modelBase;

    /** TRAINED 时的版本号，例如 v1.2.3 */
    private String version;

    /** TRAINED 时该源版本对应的 model_version.id */
    private Long versionId;

    /** TRAINED 时的最后训练完成时间 */
    private LocalDateTime lastTrainedAt;
}
