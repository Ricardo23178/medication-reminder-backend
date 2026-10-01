package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.PlanTime;
import org.apache.ibatis.annotations.Mapper;

/**
 * plan_time 表的数据库操作。
 */
@Mapper
public interface PlanTimeMapper extends BaseMapper<PlanTime> {
}
