package com.zyl.yolo.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.RedisUtils;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.BaseModelOption;
import com.zyl.yolo.dto.MetricPoint;
import com.zyl.yolo.dto.TrainCallbackRequest;
import com.zyl.yolo.dto.TrainTaskCreateRequest;
import com.zyl.yolo.dto.TrainTaskResponse;
import com.zyl.yolo.entity.Dataset;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.entity.ModelVersion;
import com.zyl.yolo.entity.TrainTask;
import com.zyl.yolo.mapper.TrainTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 训练任务服务：任务编排、入队、状态跟踪、回调
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainService extends ServiceImpl<TrainTaskMapper, TrainTask> {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** type=BASE 时可选的基础模型名单：已迁移至 DB base_model 表，见 BaseModelService。
     *  为保证向后兼容，保留一个空内存列表供旧代码引用；实际校验、下拉均走 BaseModelService。 */
    @Deprecated
    public static final List<String> SUPPORTED_BASE_MODELS = List.of();

    public static final String BASE_TYPE_BASE = "BASE";
    public static final String BASE_TYPE_TRAINED = "TRAINED";

    private final DatasetService datasetService;
    private final DatasetImageService datasetImageService;
    private final AnnotationService annotationService;
    private final LabelService labelService;
    private final ModelVersionService modelVersionService;
    private final BaseModelService baseModelService;
    private final RedisUtils redisUtils;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** 训练任务后台投递线程池：将对外 HTTP/Redis 投递移出请求线程与数据库事务，提交可立即返回 */
    private final ExecutorService dispatchExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "train-dispatch");
        t.setDaemon(true);
        return t;
    });

    @Value("${yolo.train-service.base-url:http://localhost:8000}")
    private String trainServiceBaseUrl;

    @Value("${yolo.train-service.mode:http}")
    private String trainMode;

    @Value("${yolo.train-service.redis-queue:train:queue}")
    private String redisQueue;

    @Value("${yolo.callback.token:}")
    private String callbackToken;

    /** Java 后端对外可访问的基础地址（供 Python 训练服务回调），跨机部署须指向本机对外 IP:端口 */
    @Value("${yolo.callback.base-url:http://localhost:8080}")
    private String callbackBaseUrl;
/**
     * 创建训练任务。
     * <p>注意：本方法不再整体置于事务中。校验（含大数据集）与对外投递（HTTP/Redis）均在事务外执行，
     * 仅将“任务落库 + 数据集状态更新”放入一个短事务，避免长时间持有 dataset 行锁导致并发提交锁等待超时。</p>
     */
    public TrainTaskResponse createTask(TrainTaskCreateRequest req) {
        Long datasetId = req.getDatasetId();
        String type = req.getBaseModelType() == null ? BASE_TYPE_BASE : req.getBaseModelType().toUpperCase();
        Dataset dataset = datasetService.requireDataset(datasetId);

        // 数据集完整性校验
        long imageCount = datasetImageService.countByDataset(datasetId);
        if (imageCount == 0) {
            throw new BusinessException(ResultCode.DATA_EMPTY, "数据集没有图片，无法训练");
        }
        List<Label> labels = labelService.listByDataset(datasetId);
        if (labels.isEmpty()) {
            throw new BusinessException(ResultCode.LABEL_NOT_FOUND, "数据集没有标签，无法训练");
        }
        if (!annotationService.hasAnyAnnotation(datasetId)) {
            throw new BusinessException(ResultCode.DATA_EMPTY, "数据集没有任何标注，请先完成标注并导出");
        }

        TrainTask task = new TrainTask();
        task.setDatasetId(datasetId);
        task.setBaseModelType(type);
        task.setVersionBump(req.getVersionBump() == null ? "MINOR" : req.getVersionBump().toUpperCase());
        task.setEpochs(req.getEpochs());
        task.setBatchSize(req.getBatchSize());
        task.setImgsz(req.getImgsz());
        task.setStatus(STATUS_CREATED);
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());

        if (BASE_TYPE_TRAINED.equals(type)) {
            if (req.getBaseVersionId() == null) {
                throw new BusinessException(ResultCode.TRAIN_BASE_INVALID, "请选择一个已训练的模型版本");
            }
            ModelVersion base = modelVersionService.getById(req.getBaseVersionId());
            if (base == null) {
                throw new BusinessException(ResultCode.MODEL_VERSION_NOT_FOUND);
            }
            if (base.getLastPtKey() == null || base.getLastPtKey().isBlank()) {
                throw new BusinessException(ResultCode.TRAIN_BASE_INVALID,
                        "选中的版本没有 last.pt，无法续训");
            }
            task.setBaseVersionId(base.getId());
            // 推断 base 名称：优先使用 request；其次沿续训链取根模型架构名；再从 key 路径推断
            String baseName = req.getModelBase();
            if (baseName == null || baseName.isBlank()) {
                baseName = modelVersionService.rootModelBase(base);
            }
            if (baseName == null || baseName.isBlank()) {
                baseName = inferModelBaseFromKey(base.getLastPtKey());
            }
            if (baseName == null || baseName.isBlank()) {
                baseName = "yolov8n";
            }
            task.setModelBase(baseName);
            // 系列内递增：从源版本按步长升级，并避开系列内已占用版本（含其他进行中任务的预期版本）
            Long seriesId = modelVersionService.seriesIdOf(base);
            String candidate = ModelVersionService.bumpVersion(base.getVersion(), task.getVersionBump());
            task.setExpectedVersion(ModelVersionService.nextFreeVersion(candidate, task.getVersionBump(),
                    modelVersionService.occupiedVersionsInSeries(seriesId)));
        } else {
            String baseName = req.getModelBase();
            if (baseName == null || baseName.isBlank()) {
                throw new BusinessException(ResultCode.TRAIN_BASE_INVALID, "请选择一个基础模型");
            }
            if (!baseModelService.isSupported(baseName)) {
                throw new BusinessException(ResultCode.TRAIN_BASE_INVALID,
                        "不支持的基础模型：" + baseName);
            }
            task.setModelBase(baseName);
            task.setBaseVersionId(null);
            // 基础训练开启新系列，版本号从 v1.0.0 开始（跨系列可重复）
            task.setExpectedVersion("v1.0.0");
        }
        save(task);

        // 短事务：仅“数据集状态置 READY”这一写操作需与任务落库原子化；任务 save 已在上方完成
        if (!"READY".equals(dataset.getStatus())) {
            transactionTemplate.executeWithoutResult(status ->
                    datasetService.updateStatus(datasetId, "READY"));
        }

        // 事务提交后异步投递：不阻塞请求线程，也不占用数据库事务与 dataset 行锁
        dispatchExecutor.submit(() -> dispatch(task));
        return toResponse(task);
    }

    /** 从存储 key 推断基础模型名（在路径中包含 yolov8n 等字样时） */
    private String inferModelBaseFromKey(String key) {
        return baseModelService.inferModelBaseFromKey(key);
    }

    private void dispatch(TrainTask task) {
        Map<String, Object> payload = buildPayload(task);
        try {
            if ("redis".equalsIgnoreCase(trainMode)) {
                redisUtils.leftPush(redisQueue, objectMapper.writeValueAsString(payload));
            } else {
                // http 模式：调用 FastAPI /api/train/start
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
                restTemplate.postForEntity(trainServiceBaseUrl + "/api/train/start", entity, String.class);
            }
            // 原子条件更新：仅 CREATED → QUEUED。worker 抢单后会直接写 RUNNING，
            // 若无条件回写 QUEUED 会把 RUNNING 覆盖回排队中，直到首个 epoch 结束才恢复
            boolean queued = update(new LambdaUpdateWrapper<TrainTask>()
                    .eq(TrainTask::getId, task.getId())
                    .eq(TrainTask::getStatus, STATUS_CREATED)
                    .set(TrainTask::getStatus, STATUS_QUEUED)
                    .set(TrainTask::getErrorMsg, null));
            if (!queued) {
                log.info("任务 {} 状态已被 worker 推进，跳过 QUEUED 回写", task.getId());
            }
        } catch (Exception e) {
            log.warn("训练任务投递失败，标记为 FAILED: {}", e.getMessage());
            markDispatchFailed(task, "训练服务调度失败: " + e.getMessage());
        }
    }

    /** 异步投递失败时回写任务状态，使前端能看到明确的可操作终态。
     *  仅 CREATED/QUEUED 可置 FAILED，避免覆盖 worker 已推进的 RUNNING/SUCCESS。 */
    private void markDispatchFailed(TrainTask task, String errorMsg) {
        try {
            update(new LambdaUpdateWrapper<TrainTask>()
                    .eq(TrainTask::getId, task.getId())
                    .in(TrainTask::getStatus, STATUS_CREATED, STATUS_QUEUED)
                    .set(TrainTask::getStatus, STATUS_FAILED)
                    .set(TrainTask::getErrorMsg, errorMsg)
                    .set(TrainTask::getFinishedAt, LocalDateTime.now()));
        } catch (Exception ex) {
            log.warn("更新训练任务投递失败状态出错: {}", ex.getMessage());
        }
    }

    private Map<String, Object> buildPayload(TrainTask task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("datasetId", task.getDatasetId());
        payload.put("modelBase", task.getModelBase());
        payload.put("baseModelType", task.getBaseModelType());
        payload.put("baseVersionId", task.getBaseVersionId());
        payload.put("expectedVersion", task.getExpectedVersion());
        // 存储目录前缀：版本号跨系列可重复，用任务 id 保证权重目录唯一
        payload.put("storagePrefix", "task-" + task.getId());
        payload.put("epochs", task.getEpochs());
        payload.put("batchSize", task.getBatchSize());
        payload.put("imgsz", task.getImgsz());
        payload.put("callbackUrl", callbackBaseUrl + "/api/train/callback");
        payload.put("callbackToken", callbackToken == null ? "" : callbackToken);
        return payload;
    }
public TrainTaskResponse getTask(Long taskId) {
        TrainTask task = requireTask(taskId);
        return toResponse(task);
    }

    public List<TrainTaskResponse> listTasks() {
        return list(new LambdaQueryWrapper<TrainTask>().orderByDesc(TrainTask::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private TrainTaskResponse toResponse(TrainTask task) {
        TrainTaskResponse resp = TrainTaskResponse.from(task);
        if (task.getDatasetId() != null) {
            Dataset dataset = datasetService.getById(task.getDatasetId());
            if (dataset != null) {
                resp.setDatasetName(dataset.getName());
            }
        }
        if (task.getBaseVersionId() != null) {
            ModelVersion base = modelVersionService.getById(task.getBaseVersionId());
            if (base != null) {
                resp.setBaseVersionName(base.getVersion());
            }
        }
        return resp;
    }

    public TrainTask requireTask(Long taskId) {
        TrainTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.TRAIN_TASK_NOT_FOUND);
        }
        return task;
    }

    /**
     * 取消训练任务：通知 FastAPI 终止，同时写入 Redis 取消标志（worker 每个 epoch 会检查）。
     * 已完成的任务不允许取消。
     */
    public void cancelTask(Long taskId) {
        TrainTask task = requireTask(taskId);
        if (STATUS_SUCCESS.equals(task.getStatus())
                || STATUS_FAILED.equals(task.getStatus())
                || STATUS_CANCELLED.equals(task.getStatus())) {
            throw new BusinessException(ResultCode.TRAIN_TASK_STATE_ERROR,
                    "任务状态为 " + task.getStatus() + "，不允许取消");
        }
        // 1) 通知 FastAPI 取消（会设置 Redis 标志并 revoke Celery 任务）
        try {
            restTemplate.postForEntity(
                    trainServiceBaseUrl + "/api/train/" + taskId + "/cancel", null, String.class);
        } catch (Exception e) {
            log.warn("调用 FastAPI 取消接口失败: {}", e.getMessage());
        }
        // 2) 本地状态设为 CANCELLED
        task.setStatus(STATUS_CANCELLED);
        task.setFinishedAt(LocalDateTime.now());
        updateById(task);
    }

    /**
     * 删除训练任务记录：仅限已结束的任务（SUCCESS / FAILED / CANCELLED / CREATED）。
     * QUEUED / RUNNING 状态需先取消。
     */
    public void deleteTask(Long taskId) {
        TrainTask task = requireTask(taskId);
        if (STATUS_QUEUED.equals(task.getStatus()) || STATUS_RUNNING.equals(task.getStatus())) {
            throw new BusinessException(ResultCode.TRAIN_TASK_STATE_ERROR,
                    "运行中的任务请先取消再删除");
        }
        removeById(taskId);
    }

    /**
     * 训练指标：优先读 Redis（实时），否则返回空
     */
    public List<MetricPoint> getMetrics(Long taskId) {
        requireTask(taskId);
        String redisKey = "train:metrics:" + taskId;
        String cached = redisUtils.get(redisKey);
        if (StrUtil.isNotBlank(cached)) {
            try {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MetricPoint.class));
            } catch (Exception e) {
                log.warn("解析训练指标缓存失败: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    /**
     * 训练表单可选的模型列表：同时返回 BASE 与 TRAINED 两类，前端可根据筛选类型展示。
     */
    public List<BaseModelOption> listBaseModels() {
        List<BaseModelOption> result = new java.util.ArrayList<>();
        for (String m : baseModelService.listEnabledDownloadedNames()) {
            BaseModelOption o = new BaseModelOption();
            o.setType(BASE_TYPE_BASE);
            o.setValue(m);
            o.setLabel(m);
            o.setModelBase(m);
            result.add(o);
        }
        for (ModelVersion mv : modelVersionService.listAllForVersion()) {
            if (mv.getLastPtKey() == null || mv.getLastPtKey().isBlank()) continue;
            BaseModelOption o = new BaseModelOption();
            o.setType(BASE_TYPE_TRAINED);
            o.setValue(String.valueOf(mv.getId()));
            o.setVersion(mv.getVersion());
            o.setVersionId(mv.getId());
            o.setLastTrainedAt(mv.getLastTrainedAt() != null ? mv.getLastTrainedAt() : mv.getCreatedAt());
            String arch = modelVersionService.rootModelBase(mv);
            o.setModelBase(arch != null && !arch.isBlank() ? arch : inferModelBaseFromKey(mv.getLastPtKey()));
            // 标签不暴露真实任务 id：用模型名展示，名称未含版本号时补充版本避免跨系列重版本歧义
            String name = mv.getName();
            if (name != null && !name.isBlank()) {
                o.setLabel(name.contains(mv.getVersion()) ? name : name + "（" + mv.getVersion() + "）");
            } else {
                o.setLabel(mv.getVersion() + "（" + o.getModelBase() + "）");
            }
            result.add(o);
        }
        return result;
    }

    /**
     * FastAPI 回调：更新任务状态并注册模型版本
     */
    @Transactional
    public void handleCallback(TrainCallbackRequest request) {
        TrainTask task = requireTask(request.getTaskId());
        if (request.getProgress() != null) {
            task.setProgress(request.getProgress());
        }
        if (request.getErrorMsg() != null) {
            task.setErrorMsg(request.getErrorMsg());
        }
        if (STATUS_SUCCESS.equalsIgnoreCase(request.getStatus())) {
            task.setStatus(STATUS_SUCCESS);
            task.setFinishedAt(LocalDateTime.now());
            updateById(task);
            modelVersionService.registerFromCallback(task, request);
        } else if (STATUS_FAILED.equalsIgnoreCase(request.getStatus())) {
            task.setStatus(STATUS_FAILED);
            task.setFinishedAt(LocalDateTime.now());
            updateById(task);
        } else if (STATUS_RUNNING.equalsIgnoreCase(request.getStatus())) {
            task.setStatus(STATUS_RUNNING);
            task.setStartedAt(task.getStartedAt() == null ? LocalDateTime.now() : task.getStartedAt());
            updateById(task);
        } else {
            updateById(task);
        }
    }
}