package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.AnnotationResponse;
import com.zyl.yolo.dto.AnnotationSaveRequest;
import com.zyl.yolo.dto.AnnotationSyncItem;
import com.zyl.yolo.service.AnnotationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标注接口
 */
@RestController
@RequestMapping("/api/annotation")
@RequiredArgsConstructor
public class AnnotationController {

    private final AnnotationService annotationService;

    /**
     * 保存标注（annotationId 为空时新增）
     */
    @PostMapping
    public Result<Long> save(@Valid @RequestBody AnnotationSaveRequest request,
                             @RequestParam(required = false) Long annotationId) {
        return Result.success(annotationService.saveOrUpdate(request, annotationId));
    }

    @GetMapping("/{imageId}")
    public Result<List<AnnotationResponse>> listByImage(@PathVariable Long imageId) {
        return Result.success(annotationService.listByImage(imageId));
    }

    @DeleteMapping("/{annotationId}")
    public Result<Void> delete(@PathVariable Long annotationId) {
        annotationService.delete(annotationId);
        return Result.success();
    }

    /**
     * 批量同步某张图片的标注：有 id 则更新、无 id 则新增、DB 中多余的 id 会被删除。
     */
    @PostMapping("/sync/{imageId}")
    public Result<List<AnnotationResponse>> sync(@PathVariable Long imageId,
                                                 @Valid @RequestBody List<AnnotationSyncItem> items) {
        return Result.success(annotationService.syncImage(imageId, items));
    }
}