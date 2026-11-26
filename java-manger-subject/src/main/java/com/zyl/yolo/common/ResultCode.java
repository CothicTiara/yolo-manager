package com.zyl.yolo.common;

import lombok.Getter;

/**
 * 业务错误码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源状态冲突"),

    // 业务错误 1000+
    DATASET_NOT_FOUND(1001, "数据集不存在"),
    IMAGE_NOT_FOUND(1002, "图片不存在"),
    LABEL_NOT_FOUND(1003, "标签不存在"),
    ANNOTATION_SAVE_FAILED(1004, "标注保存失败"),
    TRAIN_TASK_NOT_FOUND(1005, "训练任务不存在"),
    TRAIN_TASK_STATE_ERROR(1006, "训练任务状态不允许该操作"),
    MODEL_VERSION_NOT_FOUND(1007, "模型版本不存在"),
    MODEL_VERSION_ACTIVE_EXISTS(1008, "已存在上线版本，请先下线"),
    STORAGE_ERROR(1100, "对象存储操作失败"),
    TRAIN_SERVICE_ERROR(1101, "调用训练服务失败"),
    DATA_EMPTY(1102, "数据集为空，无法训练"),
    FILE_TYPE_NOT_SUPPORTED(1103, "不支持的文件类型"),
    DATASET_NOT_EMPTY(1104, "数据集仍有图片，不能删除"),
    DATASET_IN_USE(1105, "数据集被训练任务引用，不能删除"),
    TRAIN_BASE_INVALID(1106, "训练基模型选择不合法或缺少权重"),
    DATASET_IMPORT_FAILED(1107, "数据集导入失败"),
    DATASET_BUSY(1108, "数据集正在后台处理中，请稍后再试"),
    BASE_MODEL_NOT_FOUND(1109, "基础模型不存在"),
    BASE_MODEL_DOWNLOAD_FAILED(1110, "基础模型下载失败"),
    MODEL_VERSION_IN_USE(1111, "模型版本被进行中的训练任务引用，不能删除"),

    INTERNAL_ERROR(500, "系统内部错误");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}