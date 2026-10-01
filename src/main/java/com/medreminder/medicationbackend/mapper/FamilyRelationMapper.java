package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.FamilyRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * family_relation 表的数据库操作。
 */
@Mapper
public interface FamilyRelationMapper extends BaseMapper<FamilyRelation> {
}
