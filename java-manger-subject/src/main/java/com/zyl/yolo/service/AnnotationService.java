package com.zyl.yolo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.AnnotationResponse;
import com.zyl.yolo.dto.AnnotationSaveRequest;
import com.zyl.yolo.dto.AnnotationSyncItem;
import com.zyl.yolo.entity.Annotation;
import com.zyl.yolo.entity.DatasetImage;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.mapper.AnnotationMapper;
import com.zyl.yolo.mapper.DatasetImageMapper;
import com.zyl.yolo.mapper.LabelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 标注服务
 */
@Service
@RequiredArgsConstructor
public class AnnotationService extends ServiceImpl<AnnotationMapper, Annotation> {

    private final DatasetImageMapper datasetImageMapper;
    private final LabelMapper labelMapper;
    private final DatasetJobRegistry jobRegistry;

    /** 根据图片 id 解析所属数据集并校验其未被后台任务占用 */
    private void assertDatasetIdleByImage(Long imageId) {
        DatasetImage image = datasetImageMapper.selectById(imageId);
        if (image != null) {
            jobRegistry.assertNotBusy(image.getDatasetId());
        }
    }

    public Long save(AnnotationSaveRequest request) {
        DatasetImage image = datasetImageMapper.selectById(request.getImageId());
        if (image == null) {
            throw new BusinessException(ResultCode.IMAGE_NOT_FOUND);
        }
        jobRegistry.assertNotBusy(image.getDatasetId());
        Label label = labelMapper.selectById(request.getLabelId());
        if (label == null) {
            throw new BusinessException(ResultCode.LABEL_NOT_FOUND);
        }
        Annotation annotation = new Annotation();
        annotation.setImageId(request.getImageId());
        annotation.setLabelId(request.getLabelId());
        annotation.setXCenter(request.getXCenter());
        annotation.setYCenter(request.getYCenter());
        annotation.setWidth(request.getWidth());
        annotation.setHeight(request.getHeight());
        save(annotation);
        return annotation.getId();
    }

    /**
     * 更新已有标注（按 id），否则新增
     */
    public Long saveOrUpdate(AnnotationSaveRequest request, Long annotationId) {
        if (annotationId != null) {
            Annotation exist = getById(annotationId);
            if (exist == null) {
                throw new BusinessException(ResultCode.ANNOTATION_SAVE_FAILED, "标注不存在");
            }
            assertDatasetIdleByImage(exist.getImageId());
            exist.setLabelId(request.getLabelId());
            exist.setXCenter(request.getXCenter());
            exist.setYCenter(request.getYCenter());
            exist.setWidth(request.getWidth());
            exist.setHeight(request.getHeight());
            updateById(exist);
            return exist.getId();
        }
        return save(request);
    }

    public List<AnnotationResponse> listByImage(Long imageId) {
        DatasetImage image = datasetImageMapper.selectById(imageId);
        if (image == null) {
            throw new BusinessException(ResultCode.IMAGE_NOT_FOUND);
        }
        Long datasetId = image.getDatasetId();
        Map<Long, Label> labelMap = labelMapper.selectList(
                        new LambdaQueryWrapper<Label>().eq(Label::getDatasetId, datasetId))
                .stream().collect(Collectors.toMap(Label::getId, Function.identity()));

        return list(new LambdaQueryWrapper<Annotation>()
                        .eq(Annotation::getImageId, imageId))
                .stream()
                .map(a -> {
                    AnnotationResponse resp = new AnnotationResponse();
                    resp.setId(a.getId());
                    resp.setImageId(a.getImageId());
                    resp.setLabelId(a.getLabelId());
                    resp.setXCenter(a.getXCenter());
                    resp.setYCenter(a.getYCenter());
                    resp.setWidth(a.getWidth());
                    resp.setHeight(a.getHeight());
                    Label label = labelMap.get(a.getLabelId());
                    if (label != null) {
                        resp.setLabelName(label.getName());
                        resp.setLabelCode(label.getCode());
                        resp.setLabelColor(label.getColor());
                    }
                    return resp;
                })
                .collect(Collectors.toList());
    }

    /**
     * 删除指定标注
     */
    public void delete(Long annotationId) {
        Annotation exist = getById(annotationId);
        if (exist != null) {
            assertDatasetIdleByImage(exist.getImageId());
        }
        if (!removeById(annotationId)) {
            throw new BusinessException(ResultCode.ANNOTATION_SAVE_FAILED, "标注不存在");
        }
    }

    public List<Annotation> listByImageRaw(Long imageId) {
        return list(new LambdaQueryWrapper<Annotation>()
                .eq(Annotation::getImageId, imageId)
                .orderByAsc(Annotation::getId));
    }

    /** 数据集下是否存在任意标注（单条 SQL，替代按图片逐一查询） */
    public boolean hasAnyAnnotation(Long datasetId) {
        return baseMapper.existsByDataset(datasetId) != null;
    }

    /**
     * 批量同步某张图片的标注：有 id 则更新，无 id 则新增，
     * DB 中存在但本次 list 未传的 id 会被删除。返回同步后的完整列表。
     */
    @org.springframework.transaction.annotation.Transactional
    public List<AnnotationResponse> syncImage(Long imageId, List<AnnotationSyncItem> items) {
        DatasetImage image = datasetImageMapper.selectById(imageId);
        if (image == null) {
            throw new BusinessException(ResultCode.IMAGE_NOT_FOUND);
        }
        jobRegistry.assertNotBusy(image.getDatasetId());
        List<AnnotationSyncItem> list = items == null ? java.util.Collections.emptyList() : items;

        // 1. 本次期望保留的已有 id
        java.util.Set<Long> keepIds = list.stream()
                .map(AnnotationSyncItem::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. 删除现有但本次未传的 id
        List<Annotation> existing = listByImageRaw(imageId);
        for (Annotation a : existing) {
            if (!keepIds.contains(a.getId())) {
                removeById(a.getId());
            }
        }

        // 3. 逐项 upsert
        for (AnnotationSyncItem item : list) {
            Annotation entity;
            if (item.getId() != null) {
                entity = getById(item.getId());
                if (entity == null || !imageId.equals(entity.getImageId())) {
                    throw new BusinessException(ResultCode.ANNOTATION_SAVE_FAILED,
                            "标注 id=" + item.getId() + " 不存在或不属于该图片");
                }
            } else {
                entity = new Annotation();
                entity.setImageId(imageId);
            }
            entity.setLabelId(item.getLabelId());
            entity.setXCenter(item.getXCenter());
            entity.setYCenter(item.getYCenter());
            entity.setWidth(item.getWidth());
            entity.setHeight(item.getHeight());
            if (entity.getId() == null) {
                save(entity);
            } else {
                updateById(entity);
            }
        }

        return listByImage(imageId);
    }
}