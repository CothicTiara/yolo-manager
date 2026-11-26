package com.zyl.yolo.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.ExportProgress;
import com.zyl.yolo.entity.Annotation;
import com.zyl.yolo.entity.DatasetImage;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.storage.BucketUtils;
import com.zyl.yolo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * YOLO 标注导出服务（异步后台）
 * <p>将 MySQL 中的结构化标注转换为 YOLO 格式 .txt，上传至存储 labels/ 目录。
 * 大数据集导出耗时较长，故与导入一致改为后台执行：{@link #startExport} 立即返回进度快照，
 * 前端通过 {@link #getProgress} 轮询。导出期间通过 {@link DatasetJobRegistry} 抢占数据集忙碌锁，
 * 阻止删除数据集、增删图片/标签/标注、重复触发导入导出等并发修改。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final StorageService storageService;
    private final DatasetService datasetService;
    private final DatasetImageService datasetImageService;
    private final AnnotationService annotationService;
    private final LabelService labelService;
    private final DatasetJobRegistry jobRegistry;

    /** 每个数据集一个导出任务状态；线程池支持不同数据集并行（同一数据集由 DatasetJobRegistry 互斥保证单任务） */
    private final Map<Long, ExportJob> jobs = new ConcurrentHashMap<>();
    /** 后台导出并行度：同时处理的“不同数据集”导出任务数上限 */
    private static final int EXPORT_PARALLELISM = 4;
    private final AtomicInteger exportThreadSeq = new AtomicInteger();
    private final ExecutorService exportExecutor = Executors.newFixedThreadPool(EXPORT_PARALLELISM, r -> {
        Thread t = new Thread(r, "dataset-export-" + exportThreadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /**
     * 启动一次异步导出：快速校验后立即返回进度快照，后台线程逐图写出标注。
     */
    public ExportProgress startExport(Long datasetId) {
        datasetService.requireDataset(datasetId);
        List<Label> labels = labelService.listByDataset(datasetId);
        if (labels.isEmpty()) {
            throw new BusinessException(ResultCode.LABEL_NOT_FOUND, "数据集还没有创建标签");
        }

        // 抢占数据集忙碌锁（原子）：正在进行导入/导出/上传时拒绝
        jobRegistry.acquire(datasetId, DatasetJobRegistry.Kind.EXPORT);

        Map<Long, Integer> labelCodeMap = labels.stream()
                .collect(Collectors.toMap(Label::getId, Label::getCode));
        List<DatasetImage> images = datasetImageService.list(
                new LambdaQueryWrapper<DatasetImage>().eq(DatasetImage::getDatasetId, datasetId));

        ExportJob job = new ExportJob();
        job.status = "PENDING";
        job.total.set(images.size());
        jobs.put(datasetId, job);
        try {
            exportExecutor.submit(() -> runExport(datasetId, images, labelCodeMap, job));
        } catch (RuntimeException submitEx) {
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.EXPORT);
            jobs.remove(datasetId);
            throw submitEx;
        }
        return snapshot(datasetId, job);
    }

    public ExportProgress getProgress(Long datasetId) {
        ExportJob job = jobs.get(datasetId);
        return job == null ? ExportProgress.idle(datasetId) : snapshot(datasetId, job);
    }

    private void runExport(Long datasetId, List<DatasetImage> images,
                           Map<Long, Integer> labelCodeMap, ExportJob job) {
        try {
            job.status = "PROCESSING";
            for (DatasetImage image : images) {
                String fileId = StrUtil.subBefore(StrUtil.subAfter(image.getRustfsKey(), "/", true), ".", true);
                if (StrUtil.isBlank(fileId)) {
                    fileId = String.valueOf(image.getId());
                }
                List<Annotation> annotations = annotationService.listByImageRaw(image.getId());
                job.processed.incrementAndGet();
                if (annotations.isEmpty()) {
                    continue;
                }
                String yoloText = buildYoloText(annotations, labelCodeMap);
                if (yoloText.isEmpty()) {
                    continue;
                }
                String labelKey = BucketUtils.join(String.valueOf(datasetId), "labels", fileId + ".txt");
                storageService.upload(BucketUtils.DATASETS, labelKey,
                        yoloText.getBytes(StandardCharsets.UTF_8), "text/plain");
                job.labelCount.addAndGet(annotations.size());
                job.exported.incrementAndGet();
            }

            if (job.exported.get() == 0) {
                job.status = "FAILED";
                job.error = "没有可导出的标注数据";
            } else {
                job.status = "DONE";
                job.message = String.format("导出完成：%d 张图片、%d 条标注 → %s",
                        job.exported.get(), job.labelCount.get(),
                        BucketUtils.join(String.valueOf(datasetId), "labels/"));
                // 导出成功即具备训练消费态标注，数据集状态置为已就绪
                try {
                    datasetService.updateStatus(datasetId, "READY");
                } catch (Exception e) {
                    log.warn("导出成功但更新数据集状态失败: datasetId={}", datasetId, e);
                }
            }
        } catch (Exception e) {
            log.error("数据集导出失败: datasetId={}", datasetId, e);
            job.status = "FAILED";
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.EXPORT);
        }
    }

    private ExportProgress snapshot(Long datasetId, ExportJob job) {
        ExportProgress p = new ExportProgress();
        p.setDatasetId(datasetId);
        p.setStatus(job.status);
        p.setTotalImages(job.total.get());
        p.setProcessedImages(job.processed.get());
        p.setExportedImages(job.exported.get());
        p.setLabelCount(job.labelCount.get());
        p.setMessage(job.message);
        p.setError(job.error);
        return p;
    }

    /**
     * 生成 YOLO 格式文本：每行 class x_center y_center width height（归一化）
     */
    private String buildYoloText(List<Annotation> annotations, Map<Long, Integer> labelCodeMap) {
        Map<Long, Annotation> dedupe = new HashMap<>();
        for (Annotation a : annotations) {
            dedupe.put(a.getId(), a);
        }
        StringBuilder sb = new StringBuilder();
        List<Annotation> sorted = dedupe.values().stream()
                .sorted(Comparator.comparing(Annotation::getId))
                .collect(Collectors.toList());
        for (Annotation a : sorted) {
            Integer code = labelCodeMap.get(a.getLabelId());
            if (code == null) {
                continue;
            }
            sb.append(code).append(' ')
                    .append(clamp(a.getXCenter())).append(' ')
                    .append(clamp(a.getYCenter())).append(' ')
                    .append(clamp(a.getWidth())).append(' ')
                    .append(clamp(a.getHeight())).append('\n');
        }
        return sb.toString();
    }

    private String clamp(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal v = value.min(BigDecimal.ONE).max(BigDecimal.ZERO);
        return v.stripTrailingZeros().toPlainString();
    }

    /** 单个导出任务的内部可变状态 */
    private static class ExportJob {
        volatile String status = "PENDING";
        volatile String message;
        volatile String error;
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger exported = new AtomicInteger();
        final AtomicInteger labelCount = new AtomicInteger();
    }
}
