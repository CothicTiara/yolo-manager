package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型版本
 */
@Data
@TableName("model_version")
public class ModelVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外唯一标识（32位UUID） */
    private String uuid;

    /** 版本号：v1.0.0（系列内递增，跨系列可重复） */
    private String version;

    /** 模型名称（可编辑，注册时自动生成默认名） */
    private String name;

    /** 系列根模型 id（初次训练/首次上传的模型），同系列续训/上传共享 */
    private Long seriesId;

    private Long taskId;

    /** best.pt 的存储路径 */
    private String bestPtKey;

    /** last.pt 的存储路径 */
    private String lastPtKey;

    /** mAP@0.50 */
    private BigDecimal map50;

    /** mAP@0.50:0.95 */
    @TableField("map50_95")
    private BigDecimal map5095;

    /** 是否当前上线版本 */
    private Integer isActive;

    /** 最后训练完成时间 */
    private LocalDateTime lastTrainedAt;

    private LocalDateTime createdAt;
}