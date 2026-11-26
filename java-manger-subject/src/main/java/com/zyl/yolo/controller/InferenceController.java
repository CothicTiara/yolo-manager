package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.InferenceResponse;
import com.zyl.yolo.dto.ModelVersionResponse;
import com.zyl.yolo.entity.ModelVersion;
import com.zyl.yolo.service.InferenceService;
import com.zyl.yolo.service.ModelVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 对外推理识别接口：已训练模型公开识别能力
 */
@RestController
@RequestMapping("/api/inference")
@RequiredArgsConstructor
@Tag(name = "对外推理识别", description = "已训练模型公开识别接口：上传图片，返回标注图片与识别结果")
public class InferenceController {

    private final ModelVersionService modelVersionService;
    private final InferenceService inferenceService;

    /**
     * 对外识别接口：上传图片，返回标注图片（base64 + URL）及检测结果
     */
    @PostMapping("/detect")
    @Operation(summary = "图片识别", description = "上传一张图片执行目标检测。modelUuid 可选：不传时使用当前已上线（is_active=1）的默认模型，"
            + "传入时按 GET /api/inference/models 返回的 uuid 指定模型。响应包含检测结果、服务端标注图片（base64 与代理 URL）、原图尺寸与模型信息。")
    public Result<InferenceResponse> detect(
            @Parameter(description = "待识别图片文件（jpg/jpeg/png/bmp/webp）", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "模型唯一标识（32位UUID），不传则使用已上线的默认模型")
            @RequestParam(value = "modelUuid", required = false) String modelUuid) {
        ModelVersion mv = (modelUuid == null || modelUuid.isBlank())
                ? modelVersionService.getActiveModel()
                : modelVersionService.requireByUuid(modelUuid.trim());
        InferenceResponse resp = inferenceService.testModel(mv.getId(), file);
        resp.setModelUuid(mv.getUuid());
        resp.setModelVersion(mv.getVersion());
        return Result.success(resp);
    }

    /**
     * 可用模型列表：供调用方发现模型 uuid
     */
    @GetMapping("/models")
    @Operation(summary = "可用模型列表", description = "返回全部模型版本（含 uuid、version、isActive、mAP 指标），供调用方选择识别模型。"
            + "isActive=1 的版本为不传 modelUuid 时的默认模型。")
    public Result<List<ModelVersionResponse>> models() {
        return Result.success(modelVersionService.listAll());
    }
}