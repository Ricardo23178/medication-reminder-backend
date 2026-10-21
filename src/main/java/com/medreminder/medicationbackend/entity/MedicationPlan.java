package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对应 medication_plan 表（用药计划主表）。
 * 状态机：DRAFT（草稿）→ ACTIVE（执行中）⇄ PAUSED（暂停）→ FINISHED（自然结束）/ CANCELLED（取消）。
 * 一个计划对应多个每日提醒时间点（plan_time 表），是一对多关系。
 */
@Data
@TableName("medication_plan")
public class MedicationPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（越权过滤字段） */
    private Long userId;

    /** 关联药品档案 */
    private Long medicineId;

    /** 规则版本，修改、暂停和恢复时递增。 */
    private Integer planVersion = 1;

    /** 单次服用量 */
    private BigDecimal dosagePerTime;

    /** 服用量单位 */
    private String dosageUnit;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期，null=长期用药 */
    private LocalDate endDate;

    /** 0=无关 1=餐前 2=餐后 3=晨起空腹 4=睡前 */
    private Integer mealRelation;

    /** DRAFT/ACTIVE/PAUSED/FINISHED/CANCELLED */
    private String status;

    /** 备注（处方来源等） */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
