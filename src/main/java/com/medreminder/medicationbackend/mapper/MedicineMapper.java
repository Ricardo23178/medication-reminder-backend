package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.Medicine;
import org.apache.ibatis.annotations.Mapper;

/**
 * medicine 表的数据库操作。继承 BaseMapper，单表 CRUD 免写 SQL。
 */
@Mapper
public interface MedicineMapper extends BaseMapper<Medicine> {
}
