package com.zyl.yolo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 推理测试响应
 */
@Data
@Schema(description = "推理识别响应")
public class InferenceResponse {

    /** 检测结果 */
    @Schema(description = "检测到的目标列表")
    private List<Detection> detections;

    /** 推理耗时（毫秒） */
    @Schema(description = "推理耗时（毫秒）")
    private Integer inferenceTimeMs;

    @Schema(description = "服务端标注图片 Base64（data:image/jpeg;base64,...），可直接用于 img src")
    private String annotatedImageBase64;

    @Schema(description = "服务端标注图片访问 URL（后端代理路径）")
    private String annotatedImageUrl;

    @Schema(description = "原图宽度（像素）")
    private Integer imageWidth;

    @Schema(description = "原图高度（像素）")
    private Integer imageHeight;

    @Schema(description = "本次识别使用的模型唯一标识（32位UUID）")
    private String modelUuid;

    @Schema(description = "本次识别使用的模型版本号")
    private String modelVersion;

    @Data
    @Schema(description = "单个检测目标")
    public static class Detection {
        @Schema(description = "类别名称")
        private String label;

        @Schema(description = "类别 ID")
        private Integer labelId;

        @Schema(description = "置信度（0~1）")
        private Double confidence;

        @Schema(description = "检测框左上角 X（像素）")
        private Double x1;

        @Schema(description = "检测框左上角 Y（像素）")
        private Double y1;

        @Schema(description = "检测框右下角 X（像素）")
        private Double x2;

        @Schema(description = "检测框右下角 Y（像素）")
        private Double y2;
    }
}