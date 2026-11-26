package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据集
 */
@Data
@TableName("dataset")
public class Dataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 训练集比例，默认 0.8 */
    private BigDecimal trainRatio;

    /** READY / BUILDING */
    private String status;

    private LocalDateTime createdAt;
}