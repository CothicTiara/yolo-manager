package com.zyl.yolo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zyl.yolo.entity.Dataset;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
}