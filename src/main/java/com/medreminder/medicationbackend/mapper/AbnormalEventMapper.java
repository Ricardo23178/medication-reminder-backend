package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AbnormalEventMapper extends BaseMapper<AbnormalEvent> {
}
