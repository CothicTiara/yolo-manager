package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.DatasetCreateRequest;
import com.zyl.yolo.dto.ImportProgress;
import com.zyl.yolo.dto.DatasetResponse;
import com.zyl.yolo.service.DatasetImportService;
import com.zyl.yolo.service.DatasetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据集管理接口
 */
@RestController
@RequestMapping("/api/dataset")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetImportService datasetImportService;

    @PostMapping
    public Result<DatasetResponse> create(@Valid @RequestBody DatasetCreateRequest request) {
        return Result.success(datasetService.create(request));
    }

    @GetMapping
    public Result<List<DatasetResponse>> list() {
        return Result.success(datasetService.listAll());
    }

    @GetMapping("/{id}")
    public Result<DatasetResponse> detail(@PathVariable Long id) {
        return Result.success(datasetService.getDetail(id));
    }

    /**
     * 删除数据集：仅当未上传图片且未被训练任务引用时允许。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasetService.deleteDataset(id);
        return Result.success();
    }

    /**
     * 导入数据集（异步）：上传 zip 压缩包后立即返回，后台处理，通过 /import 状态接口轮询进度。
     * format = AUTO（默认自动探测）/ COCO / YOLO / IMAGES。
     */
    @PostMapping("/{id}/import")
    public Result<ImportProgress> importArchive(@PathVariable Long id,
                                                @RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "format", required = false, defaultValue = "AUTO") String format,
                                                @RequestParam(value = "jsonPath", required = false) String jsonPath) {
        return Result.success(datasetImportService.startImport(id, file, format, jsonPath));
    }

    /**
     * 查询指定数据集的导入进度（NONE/PENDING/PROCESSING/DONE/FAILED）。
     */
    @GetMapping("/{id}/import-status")
    public Result<ImportProgress> importStatus(@PathVariable Long id) {
        return Result.success(datasetImportService.getProgress(id));
    }
}