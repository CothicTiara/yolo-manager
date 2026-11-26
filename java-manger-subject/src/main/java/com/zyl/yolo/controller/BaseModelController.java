package com.zyl.yolo.controller;

import com.zyl.yolo.common.Result;
import com.zyl.yolo.dto.BaseModelDownloadRequest;
import com.zyl.yolo.dto.BaseModelInfo;
import com.zyl.yolo.dto.OfficialBaseModel;
import com.zyl.yolo.service.BaseModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 基础模型目录接口：DB 目录 + 从 Ultralytics 官方同步 + 触发下载到训练端 (.124) base_models_dir。
 */
@RestController
@RequestMapping("/api/base-model")
@RequiredArgsConstructor
public class BaseModelController {

    private final BaseModelService baseModelService;

    /** 目录全量（含未下载/失败） */
    @GetMapping("/list")
    public Result<List<BaseModelInfo>> list() {
        return Result.success(baseModelService.listAll());
    }

    /** 从 Python 训练端拉取本地文件清单并回写 DB 状态 */
    @PostMapping("/refresh-status")
    public Result<List<BaseModelInfo>> refreshStatus() {
        return Result.success(baseModelService.refreshStatusFromTrainService());
    }

    /** 从 Ultralytics 官方 GitHub Release 拉取最新可选目录（同步 upsert 到 DB） */
    @PostMapping("/sync-official")
    public Result<List<OfficialBaseModel>> syncOfficial() {
        return Result.success(baseModelService.syncFromOfficial());
    }

    /** 触发下载：Python 后台异步把权重写入 base_models_dir，DB 置 DOWNLOADING */
    @PostMapping("/download")
    public Result<List<BaseModelInfo>> download(@RequestBody BaseModelDownloadRequest req) {
        return Result.success(baseModelService.requestDownload(req.getModelNames()));
    }

    /** 启用/下架切换 */
    @PostMapping("/{id}/enabled")
    public Result<BaseModelInfo> toggleEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return Result.success(baseModelService.toggleEnabled(id, enabled));
    }

    /** 删除目录条目（仅允许非 DOWNLOADED 状态；不删除实际文件） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        baseModelService.removeModel(id);
        return Result.success();
    }
}
