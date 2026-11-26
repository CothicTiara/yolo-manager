package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.LabelCreateRequest;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.service.DatasetJobRegistry;
import com.zyl.yolo.service.LabelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 标签管理接口
 */
@RestController
@RequestMapping("/api/dataset/{datasetId}/label")
@RequiredArgsConstructor
@Tag(name = "标签管理", description = "数据集标签的创建、重命名与删除")
public class LabelController {

    private final LabelService labelService;
    private final DatasetJobRegistry jobRegistry;

    @GetMapping
    public Result<List<Label>> list(@PathVariable Long datasetId) {
        return Result.success(labelService.listByDataset(datasetId));
    }

    @PostMapping
    public Result<Label> save(@PathVariable Long datasetId,
                              @Valid @RequestBody LabelCreateRequest request) {
        jobRegistry.assertNotBusy(datasetId);
        Label label = new Label();
        label.setName(request.getName());
        label.setCode(request.getCode());
        label.setColor(request.getColor());
        return Result.success(labelService.saveLabel(datasetId, label));
    }

    @DeleteMapping("/{labelId}")
    public Result<Void> delete(@PathVariable Long datasetId, @PathVariable Long labelId) {
        jobRegistry.assertNotBusy(datasetId);
        labelService.deleteLabel(datasetId, labelId);
        return Result.success();
    }

    /**
     * 重命名标签：全部已有标注自动使用新名称（标注按 labelId 关联），code/颜色不变
     */
    @PutMapping("/{labelId}")
    @Operation(summary = "重命名标签", description = "统一修改标签名称：该标签下的全部已有标注与后续导出自动使用新名称；"
            + "类别序号 code 与颜色保持不变，同数据集内禁止重名。请求体：{\"name\": \"新名称\"}")
    public Result<Label> rename(
            @Parameter(description = "数据集 ID") @PathVariable Long datasetId,
            @Parameter(description = "标签 ID") @PathVariable Long labelId,
            @RequestBody Map<String, String> body) {
        jobRegistry.assertNotBusy(datasetId);
        return Result.success(labelService.renameLabel(datasetId, labelId, body.get("name")));
    }
}