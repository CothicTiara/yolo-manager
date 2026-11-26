package com.zyl.yolo.dto;

import lombok.Data;

/**
 * 图片标注数聚合结果（AnnotationMapper.countGroupByImage）
 */
@Data
public class ImageAnnotationCount {

    private Long imageId;

    private Long cnt;
}