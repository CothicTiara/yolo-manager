package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 标注
 */
@Data
@TableName("annotation")
public class Annotation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long imageId;

    private Long labelId;

    /** 归一化中心 X */
    private BigDecimal xCenter;

    /** 归一化中心 Y */
    private BigDecimal yCenter;

    /** 归一化宽度 */
    private BigDecimal width;

    /** 归一化高度 */
    private BigDecimal height;
}