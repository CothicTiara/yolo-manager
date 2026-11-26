package com.zyl.yolo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zyl.yolo.entity.Label;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LabelMapper extends BaseMapper<Label> {
}