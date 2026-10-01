package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.RemindTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RemindTaskMapper extends BaseMapper<RemindTask> {
}
