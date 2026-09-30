package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 表的数据库操作。
 * 继承 BaseMapper 后，selectOne / insert / updateById 等单表 CRUD 直接可用，
 * 不用写一行 SQL —— 这是 MyBatis-Plus 最大的省事点。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
