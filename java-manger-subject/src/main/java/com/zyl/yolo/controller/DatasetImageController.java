package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.ExportProgress;
import com.zyl.yolo.dto.ImageResponse;
import com.zyl.yolo.dto.ImageUploadResponse;
import com.zyl.yolo.service.DatasetImageService;
import com.zyl.yolo.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据集图片与导出接口
 */
@RestController
@RequestMapping("/api/dataset/{datasetId}")
@RequiredArgsConstructor
public class DatasetImageController {

    private final DatasetImageService imageService;
    private final ExportService exportService;

    /**
     * 上传图片
     */
    @PostMapping("/upload")
    public Result<ImageUploadResponse> upload(@PathVariable Long datasetId,
                                              @RequestParam("file") MultipartFile file) {
        return Result.success(imageService.uploadImage(datasetId, file));
    }

    /**
     * 图片列表
     */
    @GetMapping("/images")
    public Result<List<ImageResponse>> images(@PathVariable Long datasetId) {
        return Result.success(imageService.listByDataset(datasetId));
    }

    /**
     * 删除图片
     */
    @DeleteMapping("/images/{imageId}")
    public Result<Void> deleteImage(@PathVariable Long datasetId, @PathVariable Long imageId) {
        imageService.deleteImage(datasetId, imageId);
        return Result.success();
    }

    /**
     * 导出 YOLO 标注（异步）：启动后台导出任务并立即返回进度快照。
     */
    @PostMapping("/export")
    public Result<ExportProgress> export(@PathVariable Long datasetId) {
        return Result.success(exportService.startExport(datasetId));
    }

    /**
     * 查询导出进度（NONE/PENDING/PROCESSING/DONE/FAILED）。
     */
    @GetMapping("/export-status")
    public Result<ExportProgress> exportStatus(@PathVariable Long datasetId) {
        return Result.success(exportService.getProgress(datasetId));
    }
}