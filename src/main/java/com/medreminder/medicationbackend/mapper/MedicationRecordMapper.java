package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MedicationRecordMapper extends BaseMapper<MedicationRecord> {
}
