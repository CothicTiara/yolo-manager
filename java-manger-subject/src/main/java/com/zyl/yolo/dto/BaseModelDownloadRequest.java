package com.zyl.yolo.dto;

import lombok.Data;

import java.util.List;

/**
 * 触发基础模型下载到 Python 训练端的请求体。
 */
@Data
public class BaseModelDownloadRequest {

    /** 要下载的 modelName 列表（对应 base_model.model_name） */
    private List<String> modelNames;
}
