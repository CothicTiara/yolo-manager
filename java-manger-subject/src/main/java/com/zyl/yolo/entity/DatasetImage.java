package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 数据集图片
 */
@Data
@TableName("dataset_image")
public class DatasetImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    private String fileName;

    /** RustFS / 存储 key */
    private String rustfsKey;

    private Integer width;

    private Integer height;

    /** train / val / test */
    private String split;
}