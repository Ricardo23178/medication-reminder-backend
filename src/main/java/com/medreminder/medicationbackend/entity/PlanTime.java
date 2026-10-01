package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 对应 plan_time 表（一个计划的多个每日提醒时间点）。
 * 与 remind_task 的区别：plan_time 是「规则」（每天几点），
 * remind_task 是「实例」（某一天具体那一剂），规则改动不影响已生成实例。
 */
@Data
@TableName("plan_time")
public class PlanTime {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属计划 */
    private Long planId;

    /** 每日提醒时刻 HH:mm:ss */
    private LocalTime remindTime;

    /** 当日第几剂（排序展示用） */
    private Integer sequenceNo;

    /** 1=启用 0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;
}
