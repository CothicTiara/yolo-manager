package com.zyl.yolo.dto;

import com.zyl.yolo.entity.DatasetImage;
import lombok.Data;

/**
 * 图片响应
 */
@Data
public class ImageResponse {

    private Long id;
    private Long datasetId;
    private String fileName;
    private String rustfsKey;
    private String url;
    private Integer width;
    private Integer height;
    private String split;
    /** 该图片的标注数量；0 表示未标注 */
    private Long annotationCount;

    public static ImageResponse from(DatasetImage image, String publicUrl) {
        ImageResponse resp = new ImageResponse();
        resp.setId(image.getId());
        resp.setDatasetId(image.getDatasetId());
        resp.setFileName(image.getFileName());
        resp.setRustfsKey(image.getRustfsKey());
        resp.setUrl(publicUrl);
        resp.setWidth(image.getWidth());
        resp.setHeight(image.getHeight());
        resp.setSplit(image.getSplit());
        return resp;
    }
}