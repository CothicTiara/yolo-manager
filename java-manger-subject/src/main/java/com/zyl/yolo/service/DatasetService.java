package com.zyl.yolo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.DatasetCreateRequest;
import com.zyl.yolo.dto.DatasetResponse;
import com.zyl.yolo.entity.Dataset;
import com.zyl.yolo.entity.DatasetImage;
import com.zyl.yolo.entity.TrainTask;
import com.zyl.yolo.mapper.AnnotationMapper;
import com.zyl.yolo.mapper.DatasetImageMapper;
import com.zyl.yolo.mapper.DatasetMapper;
import com.zyl.yolo.mapper.TrainTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据集服务
 */
@Service
@RequiredArgsConstructor
public class DatasetService extends ServiceImpl<DatasetMapper, Dataset> {

    private final DatasetImageMapper datasetImageMapper;
    private final AnnotationMapper annotationMapper;
    private final TrainTaskMapper trainTaskMapper;
    private final DatasetJobRegistry jobRegistry;

    /**
     * 删除数据集：仅当未上传图片且未被训练任务引用时允许。
     */
    public void deleteDataset(Long id) {
        Dataset dataset = requireDataset(id);
        // 后台任务进行中（导入/导出/上传）禁止删除，避免误删正在写入的数据集
        jobRegistry.assertNotBusy(id);
        Long imageCount = datasetImageMapper.selectCount(
                new LambdaQueryWrapper<DatasetImage>().eq(DatasetImage::getDatasetId, id));
        if (imageCount != null && imageCount > 0) {
            throw new BusinessException(ResultCode.DATASET_NOT_EMPTY,
                    "数据集仍有 " + imageCount + " 张图片，请先清空");
        }
        Long taskCount = trainTaskMapper.selectCount(
                new LambdaQueryWrapper<TrainTask>().eq(TrainTask::getDatasetId, id));
        if (taskCount != null && taskCount > 0) {
            throw new BusinessException(ResultCode.DATASET_IN_USE,
                    "数据集被 " + taskCount + " 个训练任务引用，请先删除相关任务");
        }
        removeById(dataset.getId());
    }

    public DatasetResponse create(DatasetCreateRequest request) {
        Dataset dataset = new Dataset();
        BeanUtils.copyProperties(request, dataset);
        if (dataset.getTrainRatio() == null) {
            dataset.setTrainRatio(new BigDecimal("0.80"));
        }
        if (dataset.getTrainRatio().compareTo(BigDecimal.ONE) > 0
                || dataset.getTrainRatio().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "训练集比例必须在 0-1 之间");
        }
        dataset.setStatus("BUILDING");
        dataset.setCreatedAt(LocalDateTime.now());
        save(dataset);
        return getDetail(dataset.getId());
    }

    public List<DatasetResponse> listAll() {
        return list(new LambdaQueryWrapper<Dataset>().orderByDesc(Dataset::getCreatedAt))
                .stream()
                .map(d -> {
                    DatasetResponse resp = DatasetResponse.from(d);
                    Long count = datasetImageMapper.selectCount(
                            new LambdaQueryWrapper<DatasetImage>().eq(DatasetImage::getDatasetId, d.getId()));
                    resp.setImageCount(count);
                    resp.setAnnotatedCount(annotationMapper.countAnnotatedImages(d.getId()));
                    resp.setJobKind(jobKindName(d.getId()));
                    return resp;
                })
                .collect(Collectors.toList());
    }

    public DatasetResponse getDetail(Long id) {
        Dataset dataset = getById(id);
        if (dataset == null) {
            throw new BusinessException(ResultCode.DATASET_NOT_FOUND);
        }
        DatasetResponse resp = DatasetResponse.from(dataset);
        Long count = datasetImageMapper.selectCount(
                new LambdaQueryWrapper<DatasetImage>().eq(DatasetImage::getDatasetId, id));
        resp.setImageCount(count);
        resp.setAnnotatedCount(annotationMapper.countAnnotatedImages(id));
        resp.setJobKind(jobKindName(id));
        return resp;
    }

    /** 当前占用数据集的后台任务类型名（无则 null） */
    private String jobKindName(Long datasetId) {
        DatasetJobRegistry.Kind kind = jobRegistry.kindOf(datasetId);
        return kind == null ? null : kind.name();
    }

    public void updateStatus(Long id, String status) {
        Dataset dataset = getById(id);
        if (dataset == null) {
            throw new BusinessException(ResultCode.DATASET_NOT_FOUND);
        }
        dataset.setStatus(status);
        updateById(dataset);
    }

    public Dataset requireDataset(Long id) {
        Dataset dataset = getById(id);
        if (dataset == null) {
            throw new BusinessException(ResultCode.DATASET_NOT_FOUND);
        }
        return dataset;
    }
}