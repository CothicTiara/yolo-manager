package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 标签
 */
@Data
@TableName("label")
public class Label {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** YOLO 类别编号 */
    private Integer code;

    private String color;

    private Long datasetId;
}