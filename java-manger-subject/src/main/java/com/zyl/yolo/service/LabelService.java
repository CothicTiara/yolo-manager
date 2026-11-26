package com.zyl.yolo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.mapper.LabelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签服务
 */
@Service
@RequiredArgsConstructor
public class LabelService extends ServiceImpl<LabelMapper, Label> {

    public List<Label> listByDataset(Long datasetId) {
        return list(new LambdaQueryWrapper<Label>()
                .eq(Label::getDatasetId, datasetId)
                .orderByAsc(Label::getCode));
    }

    /**
     * 创建或更新标签；code 为空时自动按 dataset 内最大 code + 1 分配
     */
    public Label saveLabel(Long datasetId, Label label) {
        label.setDatasetId(datasetId);
        if (label.getCode() == null) {
            Integer maxCode = baseMapper.selectList(new LambdaQueryWrapper<Label>()
                            .eq(Label::getDatasetId, datasetId))
                    .stream().map(Label::getCode).max(Integer::compareTo).orElse(-1);
            label.setCode(maxCode + 1);
        }
        if (label.getColor() == null || label.getColor().isEmpty()) {
            label.setColor(defaultColor(label.getCode()));
        }
        saveOrUpdate(label);
        return label;
    }

    public void deleteLabel(Long datasetId, Long labelId) {
        Label label = getById(labelId);
        if (label == null || !label.getDatasetId().equals(datasetId)) {
            throw new BusinessException(ResultCode.LABEL_NOT_FOUND);
        }
        removeById(labelId);
    }

    /**
     * 重命名标签：标注通过 label_id 关联，改名后全部已有标注与后续导出自动使用新名称，
     * code 与颜色保持不变（不影响已导出 YOLO 标注的类别序号）。
     */
    public Label renameLabel(Long datasetId, Long labelId, String newName) {
        Label label = getById(labelId);
        if (label == null || !label.getDatasetId().equals(datasetId)) {
            throw new BusinessException(ResultCode.LABEL_NOT_FOUND);
        }
        if (newName == null || newName.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签名称不能为空");
        }
        String name = newName.trim();
        long dup = count(new LambdaQueryWrapper<Label>()
                .eq(Label::getDatasetId, datasetId)
                .eq(Label::getName, name)
                .ne(Label::getId, labelId));
        if (dup > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已存在同名标签：" + name);
        }
        label.setName(name);
        updateById(label);
        return label;
    }

    private String defaultColor(int code) {
        String[] colors = {"#409EFF", "#67C23A", "#E6A23C", "#F56C6C", "#909399",
                "#9C27B0", "#00BCD4", "#FF9800", "#4CAF50", "#E91E63"};
        return colors[Math.floorMod(code, colors.length)];
    }
}