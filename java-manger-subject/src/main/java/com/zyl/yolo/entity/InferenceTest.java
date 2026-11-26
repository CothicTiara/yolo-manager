package com.zyl.yolo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推理测试记录
 */
@Data
@TableName("inference_test")
public class InferenceTest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long modelVersionId;

    /** 测试图片存储路径 */
    private String imageKey;

    /** 检测结果 JSON 字符串 */
    private String resultJson;

    /** 推理耗时（毫秒） */
    private Integer inferenceTimeMs;

    private LocalDateTime createdAt;
}