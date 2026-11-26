package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 图片上传响应
 */
@Data
public class ImageUploadResponse {

    private Long fileId;
    private String fileName;
    private String rustfsKey;
    private String url;
    private Integer width;
    private Integer height;
}