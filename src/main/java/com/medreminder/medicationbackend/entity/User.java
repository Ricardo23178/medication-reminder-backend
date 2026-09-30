package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 sys_user 表（账户与角色）。
 * 字段名用驼峰，MyBatis-Plus 靠 map-underscore-to-camel-case 自动映射下划线。
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（手机号或用户名） */
    private String account;

    /** 密码哈希，永远不回传给前端 */
    @JsonIgnore
    private String passwordHash;

    private String nickname;

    private String phone;

    /** USER=普通用户, ADMIN=平台管理员 */
    private String role;

    /** 1=启用, 0=禁用 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：查询时 MyBatis-Plus 自动过滤已删记录 */
    @TableLogic
    private Integer deleted;
}
