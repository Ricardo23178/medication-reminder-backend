package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 medicine 表（用户药品档案）。
 * 合规边界：只存用户自己录入的名称/规格/备注/图片，不存适应症、用法建议等药学知识，
 * 系统不做药事顾问 —— 这是导师 D 级禁区的落地。
 */
@Data
@TableName("medicine")
public class Medicine {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 —— 数据级权限的核心字段，查询/修改/删除都按它过滤 */
    private Long userId;

    private String name;

    private String specification;

    private String dosage;

    private String unit;

    private String remark;

    private String imageUrl;

    /** 1=启用, 0=停用（停用不影响历史记录） */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
