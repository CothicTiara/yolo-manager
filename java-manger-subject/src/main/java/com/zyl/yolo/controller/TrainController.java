package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.BaseModelOption;
import com.zyl.yolo.dto.MetricPoint;
import com.zyl.yolo.dto.TrainCallbackRequest;
import com.zyl.yolo.dto.TrainTaskCreateRequest;
import com.zyl.yolo.dto.TrainTaskResponse;
import com.zyl.yolo.service.TrainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 训练任务接口
 */
@RestController
@RequestMapping("/api/train")
@RequiredArgsConstructor
public class TrainController {

    private final TrainService trainService;

    /**
     * 创建训练任务（支持基础训练 + 已训练模型续训）
     */
    @PostMapping
    public Result<TrainTaskResponse> create(@Valid @RequestBody TrainTaskCreateRequest request) {
        return Result.success(trainService.createTask(request));
    }

    /**
     * 训练表单可选模型列表（base + trained 同时返回，前端按 type 筛选）
     */
    @GetMapping("/base-models")
    public Result<List<BaseModelOption>> baseModels() {
        return Result.success(trainService.listBaseModels());
    }

    @GetMapping
    public Result<List<TrainTaskResponse>> list() {
        return Result.success(trainService.listTasks());
    }

    @GetMapping("/{id}")
    public Result<TrainTaskResponse> detail(@PathVariable Long id) {
        return Result.success(trainService.getTask(id));
    }

    @GetMapping("/{id}/metrics")
    public Result<List<MetricPoint>> metrics(@PathVariable Long id) {
        return Result.success(trainService.getMetrics(id));
    }

    /**
     * FastAPI 训练结果回调
     */
    @PostMapping("/callback")
    public Result<Void> callback(@RequestBody TrainCallbackRequest request) {
        trainService.handleCallback(request);
        return Result.success();
    }

    /**
     * 取消训练任务（运行中或队列中）
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        trainService.cancelTask(id);
        return Result.success();
    }

    /**
     * 删除训练任务记录（仅限已结束的任务）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        trainService.deleteTask(id);
        return Result.success();
    }
}