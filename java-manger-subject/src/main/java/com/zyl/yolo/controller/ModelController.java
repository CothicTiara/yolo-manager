package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.InferenceResponse;
import com.zyl.yolo.dto.ModelVersionResponse;
import com.zyl.yolo.entity.InferenceTest;
import com.zyl.yolo.service.InferenceService;
import com.zyl.yolo.service.ModelVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 模型版本与管理接口
 */
@RestController
@RequestMapping("/api/model")
@RequiredArgsConstructor
@Tag(name = "模型版本管理", description = "模型版本列表、上传注册、名称编辑、激活上线、调用测试、下载与删除")
public class ModelController {

    private final ModelVersionService modelVersionService;
    private final InferenceService inferenceService;

    @GetMapping
    @Operation(summary = "模型版本列表", description = "返回全部模型版本，含 uuid、版本号、模型名称、系列根 ID（seriesId，同系列续训/同名上传共享，前端按此折叠）、基底模型（续训链根的架构名）、使用的训练集（可多个）、上线状态")
    public Result<List<ModelVersionResponse>> list() {
        return Result.success(modelVersionService.listAll());
    }

    /**
     * 编辑模型名称
     */
    @PutMapping("/{id}/name")
    @Operation(summary = "编辑模型名称", description = "修改模型版本的自定义名称（非空、最长 100 字符）；不影响版本号与 UUID。请求体：{\"name\": \"新名称\"}")
    public Result<ModelVersionResponse> rename(
            @Parameter(description = "模型版本 ID") @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return Result.success(modelVersionService.renameModel(id, body.get("name")));
    }

    /**
     * 手动上传训练好的 YOLO 模型权重注册新版本
     */
    @PostMapping("/upload")
    @Operation(summary = "上传模型", description = "直接上传训练好的 YOLO 权重（.pt）注册新版本：文件名（去 .pt）与库内模型同名时并入该系列并从系列最大版本递增，新名则新系列从 v1.0.0 开始；uuid 自动生成；"
            + "权重同时写入 best.pt 与 last.pt，支持推理上线与继续训练。上传后默认未上线，需手动激活。")
    public Result<ModelVersionResponse> upload(
            @Parameter(description = "YOLO 模型权重文件（.pt）") @RequestParam("file") MultipartFile file) {
        return Result.success(modelVersionService.uploadModel(file));
    }

    /**
     * 激活模型版本
     */
    @PostMapping("/{id}/activate")
    @Operation(summary = "激活上线", description = "将指定版本设为当前上线版本（对外识别接口默认模型），其他版本自动下线")
    public Result<Void> activate(
            @Parameter(description = "模型版本 ID") @PathVariable Long id) {
        modelVersionService.activate(id);
        return Result.success();
    }

    /**
     * 模型调用测试
     */
    @PostMapping("/{id}/test")
    @Operation(summary = "模型调用测试", description = "上传测试图片执行推理，返回检测结果与服务端标注图")
    public Result<InferenceResponse> test(
            @Parameter(description = "模型版本 ID") @PathVariable Long id,
            @Parameter(description = "测试图片文件") @RequestParam("file") MultipartFile file) {
        return Result.success(inferenceService.testModel(id, file));
    }

    @GetMapping("/{id}/tests")
    @Operation(summary = "测试记录列表", description = "返回指定模型版本的历史推理测试记录")
    public Result<List<InferenceTest>> tests(
            @Parameter(description = "模型版本 ID") @PathVariable Long id) {
        return Result.success(inferenceService.listTests(id));
    }

    /**
     * 下载模型权重文件（.pt）
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "下载模型", description = "以附件形式下载指定版本的 best.pt 权重文件，文件名为 {版本号}.pt")
    public ResponseEntity<byte[]> download(
            @Parameter(description = "模型版本 ID") @PathVariable Long id) {
        var mv = modelVersionService.require(id);
        byte[] content = modelVersionService.downloadWeights(id);
        String filename = mv.getVersion() + ".pt";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.length)
                .body(content);
    }

    /**
     * 删除模型版本
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型版本", description = "删除模型版本记录；无其他版本共享的 S3 权重一并删除，测试记录级联清理。"
            + "被进行中的续训任务引用时禁止删除；删除上线版本后对外接口将无默认模型。")
    public Result<Void> delete(
            @Parameter(description = "模型版本 ID") @PathVariable Long id) {
        modelVersionService.deleteModel(id);
        return Result.success();
    }
}