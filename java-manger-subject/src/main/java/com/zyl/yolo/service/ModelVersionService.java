package com.zyl.yolo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.ModelVersionResponse;
import com.zyl.yolo.dto.TrainCallbackRequest;
import com.zyl.yolo.entity.Dataset;
import com.zyl.yolo.entity.InferenceTest;
import com.zyl.yolo.entity.ModelVersion;
import com.zyl.yolo.entity.TrainTask;
import com.zyl.yolo.mapper.DatasetMapper;
import com.zyl.yolo.mapper.InferenceTestMapper;
import com.zyl.yolo.mapper.ModelVersionMapper;
import com.zyl.yolo.mapper.TrainTaskMapper;
import com.zyl.yolo.storage.BucketUtils;
import com.zyl.yolo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型版本服务：注册、激活、列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelVersionService extends ServiceImpl<ModelVersionMapper, ModelVersion> {

    private final InferenceTestMapper inferenceTestMapper;
    private final TrainTaskMapper trainTaskMapper;
    private final DatasetMapper datasetMapper;
    private final StorageService storageService;

    /**
     * 训练回调成功后注册模型版本（按 task_id 幂等）：
     * Python 训练端会先直写库注册一次，回调到达时若该任务已有版本则仅更新，
     * 避免同一任务重复插入两个版本（如 v0.1.3 / v0.1.4）。
     */
    @Transactional
    public ModelVersionResponse registerFromCallback(TrainTask task, TrainCallbackRequest request) {
        // 幂等：同一任务已注册过版本（Python 直写路径）时只更新指标与权重，不重复插入
        if (task != null) {
            ModelVersion existing = getOne(new LambdaQueryWrapper<ModelVersion>()
                    .eq(ModelVersion::getTaskId, task.getId())
                    .orderByAsc(ModelVersion::getId)
                    .last("LIMIT 1"), false);
            if (existing != null) {
                existing.setBestPtKey(request.getBestPtKey());
                existing.setLastPtKey(request.getLastPtKey());
                if (existing.getName() == null || existing.getName().isBlank()) {
                    existing.setName(defaultTrainedName(task, existing.getVersion()));
                }
                // Python 直写路径不含 series_id，回调时补填系列归属
                if (existing.getSeriesId() == null) {
                    Long sid = seriesIdForTask(task);
                    existing.setSeriesId(sid != null ? sid : existing.getId());
                }
                if (request.getMap50() != null) {
                    existing.setMap50(request.getMap50());
                }
                if (request.getMap5095() != null) {
                    existing.setMap5095(request.getMap5095());
                }
                existing.setLastTrainedAt(LocalDateTime.now());
                updateById(existing);
                log.info("模型版本已存在（同任务幂等更新）: taskId={}, version={}, modelId={}",
                        task.getId(), existing.getVersion(), existing.getId());
                return ModelVersionResponse.from(existing);
            }
        }
        // 系列归属：续训→源模型系列；基础训练→新系列（save 后回填自身 id）
        Long seriesId = seriesIdForTask(task);
        // 优先级：task.expectedVersion > request.version > 系列内递增（新系列从 v1.0.0 开始）
        String version = task == null ? null : task.getExpectedVersion();
        if (version == null || version.isBlank()) {
            version = request.getVersion();
        }
        if (version == null || version.isBlank()) {
            version = seriesId == null ? "v1.0.0" : nextVersionInSeries(seriesId, "PATCH");
        }
        // 系列内避重（跨系列版本号允许重复）
        if (seriesId != null) {
            Set<String> occupied = occupiedVersionsInSeries(seriesId);
            version = nextFreeVersion(version, "PATCH", occupied);
        }
        LocalDateTime now = LocalDateTime.now();
        ModelVersion mv = new ModelVersion();
        mv.setUuid(UUID.randomUUID().toString().replace("-", ""));
        mv.setVersion(version);
        mv.setName(defaultTrainedName(task, version));
        mv.setSeriesId(seriesId);
        mv.setTaskId(task == null ? null : task.getId());
        mv.setBestPtKey(request.getBestPtKey());
        mv.setLastPtKey(request.getLastPtKey());
        mv.setMap50(request.getMap50() == null ? BigDecimal.ZERO : request.getMap50());
        mv.setMap5095(request.getMap5095() == null ? BigDecimal.ZERO : request.getMap5095());
        mv.setIsActive(0);
        mv.setLastTrainedAt(now);
        mv.setCreatedAt(now);
        save(mv);
        // 新系列：系列根即自身，save 拿到 id 后回填
        if (mv.getSeriesId() == null) {
            mv.setSeriesId(mv.getId());
            updateById(mv);
        }
        log.info("模型版本注册成功: taskId={}, version={}, modelId={}, seriesId={}",
                task == null ? null : task.getId(), version, mv.getId(), mv.getSeriesId());
        return ModelVersionResponse.from(mv);
    }

    public List<ModelVersion> listAllForVersion() {
        return list(new LambdaQueryWrapper<ModelVersion>().orderByDesc(ModelVersion::getCreatedAt));
    }

    /**
     * 手动上传训练好的 YOLO 权重（.pt）注册模型版本：
     * 按文件名（去 .pt）匹配库内同名模型：同名→并入该系列并从系列最大版本 PATCH 递增；
     * 新名→新系列从 v1.0.0 开始。uuid 自动生成（v4 随机）；同一份权重同时写入 best.pt 与 last.pt，
     * 使上传的模型既可直接推理/上线，也可作为续训源。
     */
    public ModelVersionResponse uploadModel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要上传的模型文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pt")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 .pt 格式的 YOLO 模型权重文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败：" + e.getMessage());
        }
        // 默认名称取上传文件名（去 .pt 后缀），后续可在列表中编辑
        String baseName = filename.substring(0, filename.length() - 3).trim();
        String name = baseName.isEmpty() ? null
                : baseName.substring(0, Math.min(baseName.length(), 100));
        ModelVersion sameName = name == null ? null : getOne(new LambdaQueryWrapper<ModelVersion>()
                .eq(ModelVersion::getName, name)
                .orderByAsc(ModelVersion::getId)
                .last("LIMIT 1"), false);
        String version;
        Long seriesId = null;
        if (sameName != null) {
            seriesId = seriesIdOf(sameName);
            version = nextFreeVersion(nextVersionInSeries(seriesId, "PATCH"), "PATCH",
                    occupiedVersionsInSeries(seriesId));
        } else {
            version = "v1.0.0";
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        // 版本号跨系列可重复，存储目录用 uuid 保证唯一，避免权重覆盖
        String bestKey = "uploads/" + uuid + "/best.pt";
        String lastKey = "uploads/" + uuid + "/last.pt";
        storageService.upload(BucketUtils.MODELS, bestKey, content, "application/octet-stream");
        storageService.upload(BucketUtils.MODELS, lastKey, content, "application/octet-stream");
        LocalDateTime now = LocalDateTime.now();
        ModelVersion mv = new ModelVersion();
        mv.setUuid(uuid);
        mv.setVersion(version);
        mv.setName(name == null ? "手动上传 " + version : name);
        mv.setSeriesId(seriesId);
        mv.setTaskId(null);
        mv.setBestPtKey(bestKey);
        mv.setLastPtKey(lastKey);
        mv.setMap50(BigDecimal.ZERO);
        mv.setMap5095(BigDecimal.ZERO);
        mv.setIsActive(0);
        mv.setLastTrainedAt(now);
        mv.setCreatedAt(now);
        save(mv);
        if (mv.getSeriesId() == null) {
            mv.setSeriesId(mv.getId());
            updateById(mv);
        }
        log.info("手动上传模型版本注册成功: version={}, modelId={}, seriesId={}, uuid={}, size={}KB",
                version, mv.getId(), mv.getSeriesId(), mv.getUuid(), content.length / 1024);
        return ModelVersionResponse.from(mv);
    }

    public List<ModelVersionResponse> listAll() {
        List<ModelVersion> models = list(new LambdaQueryWrapper<ModelVersion>()
                .orderByDesc(ModelVersion::getCreatedAt));
        // 一次性加载任务/版本/数据集映射，沿续训链回溯基底模型与全部训练集（避免逐行 N+1）
        Map<Long, TrainTask> taskMap = trainTaskMapper.selectList(null).stream()
                .collect(Collectors.toMap(TrainTask::getId, Function.identity()));
        Map<Long, ModelVersion> modelMap = models.stream()
                .collect(Collectors.toMap(ModelVersion::getId, Function.identity()));
        Map<Long, String> datasetNames = datasetMapper.selectList(null).stream()
                .collect(Collectors.toMap(Dataset::getId, Dataset::getName));
        return models.stream().map(mv -> {
            ModelVersionResponse resp = ModelVersionResponse.from(mv);
            fillTrainingInfo(resp, mv, taskMap, modelMap, datasetNames);
            return resp;
        }).collect(Collectors.toList());
    }

    /**
     * 填充基底模型与训练集列表：沿续训链回溯到根模型，基底模型显示根任务的架构名
     *（根为手动上传时显示「手动上传」），并聚合历次训练集。
     * 名称为空时回退默认名；series_id 为空时沿链计算兜底（兼容存量/Python 直写数据）。
     */
    private void fillTrainingInfo(ModelVersionResponse resp, ModelVersion mv,
                                  Map<Long, TrainTask> taskMap, Map<Long, ModelVersion> modelMap,
                                  Map<Long, String> datasetNames) {
        TrainTask task = mv.getTaskId() == null ? null : taskMap.get(mv.getTaskId());
        if (task == null) {
            resp.setBaseModel("手动上传");
            resp.setDatasets(List.of());
            if (resp.getSeriesId() == null) {
                resp.setSeriesId(mv.getId());
            }
            if (resp.getName() == null || resp.getName().isBlank()) {
                resp.setName("手动上传 " + mv.getVersion());
            }
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        Set<Long> visited = new HashSet<>();
        TrainTask cur = task;
        ModelVersion rootMv = mv;
        while (cur != null && visited.add(cur.getId())) {
            String dn = cur.getDatasetId() == null ? null : datasetNames.get(cur.getDatasetId());
            names.add(dn != null ? dn : "已删除数据集");
            if (!"TRAINED".equals(cur.getBaseModelType()) || cur.getBaseVersionId() == null) {
                break;
            }
            ModelVersion src = modelMap.get(cur.getBaseVersionId());
            if (src == null) {
                break;
            }
            rootMv = src;
            cur = src.getTaskId() == null ? null : taskMap.get(src.getTaskId());
        }
        // 基底模型 = 系列根模型的架构名；根是上传模型（无任务）时为「手动上传」
        TrainTask rootTask = rootMv.getTaskId() == null ? null : taskMap.get(rootMv.getTaskId());
        resp.setBaseModel(rootTask == null ? "手动上传" : rootTask.getModelBase());
        resp.setDatasets(new ArrayList<>(names));
        if (resp.getSeriesId() == null) {
            resp.setSeriesId(seriesIdOf(rootMv));
        }
        if (resp.getName() == null || resp.getName().isBlank()) {
            String first = names.isEmpty() ? "模型" : names.iterator().next();
            resp.setName(first + " " + mv.getVersion());
        }
    }

    /**
     * 训练注册时的默认模型名称：数据集名 + 版本号
     */
    private String defaultTrainedName(TrainTask task, String version) {
        String datasetName = null;
        if (task != null && task.getDatasetId() != null) {
            Dataset ds = datasetMapper.selectById(task.getDatasetId());
            datasetName = ds == null ? null : ds.getName();
        }
        String name = (datasetName == null ? "模型" : datasetName) + " " + version;
        return name.substring(0, Math.min(name.length(), 100));
    }

    /**
     * 编辑模型名称
     */
    public ModelVersionResponse renameModel(Long modelId, String newName) {
        ModelVersion mv = require(modelId);
        if (newName == null || newName.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型名称不能为空");
        }
        String name = newName.trim();
        if (name.length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型名称长度不能超过 100");
        }
        mv.setName(name);
        updateById(mv);
        log.info("模型名称已更新: id={}, version={}, name={}", modelId, mv.getVersion(), name);
        return ModelVersionResponse.from(mv);
    }

    /**
     * 激活模型版本：先将其他版本下线，再激活指定版本
     */
    @Transactional
    public void activate(Long modelId) {
        ModelVersion mv = require(modelId);
        if (mv.getIsActive() != null && mv.getIsActive() == 1) {
            return;
        }
        List<ModelVersion> activeList = list(new LambdaQueryWrapper<ModelVersion>()
                .eq(ModelVersion::getIsActive, 1));
        for (ModelVersion active : activeList) {
            active.setIsActive(0);
            updateById(active);
        }
        mv.setIsActive(1);
        updateById(mv);
        log.info("模型版本已激活: id={}, version={}", modelId, mv.getVersion());
    }

    public ModelVersion require(Long modelId) {
        ModelVersion mv = getById(modelId);
        if (mv == null) {
            throw new BusinessException(ResultCode.MODEL_VERSION_NOT_FOUND);
        }
        return mv;
    }

    /**
     * 下载模型权重（best.pt）字节，供控制器以附件形式返回
     */
    public byte[] downloadWeights(Long modelId) {
        ModelVersion mv = require(modelId);
        String key = mv.getBestPtKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ResultCode.MODEL_VERSION_NOT_FOUND, "该版本缺少权重文件，无法下载");
        }
        try {
            return storageService.download(BucketUtils.MODELS, key);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "权重文件下载失败：" + e.getMessage());
        }
    }

    /**
     * 删除模型版本：
     * - 被进行中（CREATED/QUEUED/RUNNING）的续训任务引用时禁止删除
     * - S3 权重仅在没有其他版本引用同一 key 时才删除（避免多版本共享权重被误删）
     * - inference_test 由外键 ON DELETE CASCADE 自动清理
     */
    @Transactional
    public void deleteModel(Long modelId) {
        ModelVersion mv = require(modelId);
        long activeRefs = trainTaskMapper.selectCount(new LambdaQueryWrapper<TrainTask>()
                .eq(TrainTask::getBaseVersionId, modelId)
                .in(TrainTask::getStatus, TrainService.STATUS_CREATED,
                        TrainService.STATUS_QUEUED, TrainService.STATUS_RUNNING));
        if (activeRefs > 0) {
            throw new BusinessException(ResultCode.MODEL_VERSION_IN_USE);
        }
        deleteKeyIfUnshared(modelId, mv.getBestPtKey());
        deleteKeyIfUnshared(modelId, mv.getLastPtKey());
        removeById(modelId);
        log.info("模型版本已删除: id={}, version={}", modelId, mv.getVersion());
    }

    private void deleteKeyIfUnshared(Long modelId, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        long refs = count(new LambdaQueryWrapper<ModelVersion>()
                .ne(ModelVersion::getId, modelId)
                .and(w -> w.eq(ModelVersion::getBestPtKey, key)
                        .or().eq(ModelVersion::getLastPtKey, key)));
        if (refs > 0) {
            log.info("模型权重仍被其他版本引用，保留: key={}", key);
            return;
        }
        try {
            storageService.delete(BucketUtils.MODELS, key);
        } catch (Exception e) {
            log.warn("删除模型权重失败（仅删 DB 记录）: key={}, {}", key, e.getMessage());
        }
    }

    /**
     * 按对外 UUID 查找模型版本，不存在则抛异常
     */
    public ModelVersion requireByUuid(String uuid) {
        ModelVersion mv = getOne(new LambdaQueryWrapper<ModelVersion>()
                .eq(ModelVersion::getUuid, uuid), false);
        if (mv == null) {
            throw new BusinessException(ResultCode.MODEL_VERSION_NOT_FOUND);
        }
        return mv;
    }

    /**
     * 获取当前上线的默认模型（is_active=1），无则抛异常
     */
    public ModelVersion getActiveModel() {
        ModelVersion mv = getOne(new LambdaQueryWrapper<ModelVersion>()
                .eq(ModelVersion::getIsActive, 1)
                .orderByDesc(ModelVersion::getLastTrainedAt)
                .last("LIMIT 1"), false);
        if (mv == null) {
            throw new BusinessException(ResultCode.MODEL_VERSION_NOT_FOUND, "没有已上线的默认模型，请先激活一个模型版本或指定 modelUuid");
        }
        return mv;
    }

    /**
     * 保存推理测试记录
     */
    public Long saveInferenceTest(Long modelVersionId, String imageKey, String resultJson, Integer inferenceTimeMs) {
        InferenceTest test = new InferenceTest();
        test.setModelVersionId(modelVersionId);
        test.setImageKey(imageKey);
        test.setResultJson(resultJson);
        test.setInferenceTimeMs(inferenceTimeMs);
        test.setCreatedAt(LocalDateTime.now());
        inferenceTestMapper.insert(test);
        return test.getId();
    }

    /**
     * 生成模型版本号（全局兜底，仅无任务上下文时使用）
     */
    public String nextVersion() {
        List<ModelVersion> versions = listAllForVersion();
        if (versions.isEmpty()) {
            return "v1.0.0";
        }
        try {
            String latest = versions.stream()
                    .map(ModelVersion::getVersion)
                    .max(ModelVersionService::compareVersions)
                    .orElse("v1.0.0");
            return bumpVersion(latest, "PATCH");
        } catch (Exception e) {
            return "v1.0.1";
        }
    }

    /**
     * 模型所属系列根 id（存量 series_id 为空时兜底自身 id）
     */
    public Long seriesIdOf(ModelVersion mv) {
        return mv.getSeriesId() != null ? mv.getSeriesId() : mv.getId();
    }

    /**
     * 任务产出模型的系列归属：续训→源模型系列；基础训练/无任务→null（新系列）
     */
    public Long seriesIdForTask(TrainTask task) {
        if (task == null || !"TRAINED".equals(task.getBaseModelType()) || task.getBaseVersionId() == null) {
            return null;
        }
        ModelVersion base = getById(task.getBaseVersionId());
        return base == null ? null : seriesIdOf(base);
    }

    /**
     * 沿续训链回溯根模型的架构名（供续训任务/下拉选项推断 modelBase）；
     * 根为手动上传或链断裂时返回 null
     */
    public String rootModelBase(ModelVersion mv) {
        Set<Long> visited = new HashSet<>();
        ModelVersion cur = mv;
        while (cur != null && visited.add(cur.getId())) {
            TrainTask t = cur.getTaskId() == null ? null : trainTaskMapper.selectById(cur.getTaskId());
            if (t == null) {
                return null;
            }
            if (!"TRAINED".equals(t.getBaseModelType()) || t.getBaseVersionId() == null) {
                return t.getModelBase();
            }
            cur = getById(t.getBaseVersionId());
        }
        return null;
    }

    /**
     * 系列内全部模型版本（兼容 series_id 尚未回填的系列根自身）
     */
    public List<ModelVersion> listBySeries(Long seriesId) {
        return list(new LambdaQueryWrapper<ModelVersion>()
                .and(w -> w.eq(ModelVersion::getSeriesId, seriesId)
                        .or(o -> o.isNull(ModelVersion::getSeriesId).eq(ModelVersion::getId, seriesId))));
    }

    /**
     * 系列内下一版本号：从系列最大版本按步长递增；空系列返回 v1.0.0
     */
    public String nextVersionInSeries(Long seriesId, String bump) {
        String max = null;
        for (ModelVersion m : listBySeries(seriesId)) {
            if (max == null || compareVersions(m.getVersion(), max) > 0) {
                max = m.getVersion();
            }
        }
        return max == null ? "v1.0.0" : bumpVersion(max, bump);
    }

    /**
     * 系列内已占用版本集合：已注册模型版本 + 进行中续训任务的预期版本
     */
    public Set<String> occupiedVersionsInSeries(Long seriesId) {
        List<ModelVersion> members = listBySeries(seriesId);
        Set<String> occupied = members.stream()
                .map(ModelVersion::getVersion)
                .collect(Collectors.toSet());
        List<Long> ids = members.stream().map(ModelVersion::getId).toList();
        if (!ids.isEmpty()) {
            trainTaskMapper.selectList(new LambdaQueryWrapper<TrainTask>()
                    .in(TrainTask::getBaseVersionId, ids)
                    .in(TrainTask::getStatus, TrainService.STATUS_CREATED,
                            TrainService.STATUS_QUEUED, TrainService.STATUS_RUNNING))
                    .forEach(t -> {
                        if (t.getExpectedVersion() != null && !t.getExpectedVersion().isBlank()) {
                            occupied.add(t.getExpectedVersion());
                        }
                    });
        }
        return occupied;
    }

    /**
     * 从候选版本起按步长顺延，避开已占用版本
     */
    public static String nextFreeVersion(String candidate, String bump, Set<String> occupied) {
        while (occupied.contains(candidate)) {
            candidate = bumpVersion(candidate, bump);
        }
        return candidate;
    }

    /**
     * 数值化版本比较（v1.10.0 > v1.9.0，避免字符串比较缺陷）
     */
    public static int compareVersions(String a, String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return Integer.compare(pa[i], pb[i]);
            }
        }
        return 0;
    }

    private static int[] parseVersionParts(String v) {
        String s = v == null ? "" : (v.startsWith("v") ? v.substring(1) : v);
        String[] parts = s.split("\\.");
        return new int[]{
                parts.length > 0 ? parseIntSafe(parts[0], 0) : 0,
                parts.length > 1 ? parseIntSafe(parts[1], 0) : 0,
                parts.length > 2 ? parseIntSafe(parts[2], 0) : 0};
    }

    /**
     * 在指定 base 版本上按步长计算下一个版本号：
     *   MINOR=+0.1 (例 v1.2.3 -> v1.3.0)
     *   MAJOR=+1.0 (例 v1.2.3 -> v2.0.0)
     *   PATCH=+0.0.1 (默认)
     */
    public static String bumpVersion(String base, String bump) {
        if (base == null || base.isBlank()) {
            return "v1.0.0";
        }
        String s = base.startsWith("v") ? base.substring(1) : base;
        String[] parts = s.split("\\.");
        int major = parts.length > 0 ? parseIntSafe(parts[0], 1) : 1;
        int minor = parts.length > 1 ? parseIntSafe(parts[1], 0) : 0;
        int patch = parts.length > 2 ? parseIntSafe(parts[2], 0) : 0;
        String b = bump == null ? "MINOR" : bump.toUpperCase();
        switch (b) {
            case "MAJOR" -> {
                major += 1;
                minor = 0;
                patch = 0;
            }
            case "PATCH" -> {
                patch += 1;
                if (patch >= 100) { patch = 0; minor += 1; }
                if (minor >= 100) { minor = 0; major += 1; }
            }
            default -> { // MINOR
                minor += 1;
                patch = 0;
                if (minor >= 100) { minor = 0; major += 1; }
            }
        }
        return "v" + major + "." + minor + "." + patch;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}