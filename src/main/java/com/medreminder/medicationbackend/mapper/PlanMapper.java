package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import org.apache.ibatis.annotations.Mapper;

/**
 * medication_plan 表的数据库操作。
 */
@Mapper
public interface PlanMapper extends BaseMapper<MedicationPlan> {
}
