package com.zyl.yolo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zyl.yolo.dto.ImageAnnotationCount;
import com.zyl.yolo.entity.Annotation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnnotationMapper extends BaseMapper<Annotation> {

    /**
     * 判断数据集下是否存在任意标注：关联 dataset_image 命中一条即返回，避免按图片逐条查询（N+1）。
     * 存在返回 1，不存在返回 null。
     */
    @Select("SELECT 1 FROM annotation a " +
            "JOIN dataset_image di ON a.image_id = di.id " +
            "WHERE di.dataset_id = #{datasetId} LIMIT 1")
    Integer existsByDataset(@Param("datasetId") Long datasetId);

    /**
     * 统计数据集下已有标注的图片数（至少一条标注即计入，按 image_id 去重）。
     */
    @Select("SELECT COUNT(DISTINCT a.image_id) FROM annotation a " +
            "JOIN dataset_image di ON a.image_id = di.id " +
            "WHERE di.dataset_id = #{datasetId}")
    Long countAnnotatedImages(@Param("datasetId") Long datasetId);

    /**
     * 按图片聚合数据集内的标注数（一次查询，避免逐图 N+1），用于图片列表标注状态角标。
     */
    @Select("SELECT a.image_id AS imageId, COUNT(*) AS cnt FROM annotation a " +
            "JOIN dataset_image di ON a.image_id = di.id " +
            "WHERE di.dataset_id = #{datasetId} GROUP BY a.image_id")
    List<ImageAnnotationCount> countGroupByImage(@Param("datasetId") Long datasetId);
}
