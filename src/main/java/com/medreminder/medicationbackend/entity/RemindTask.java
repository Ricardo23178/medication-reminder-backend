package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 remind_task 表（具体提醒任务）。
 * 状态机：WAITING（等待）→ TRIGGERED（已触发）→ CONFIRMED（已确认）
 *                                              ↘ EXPIRED（超时，产生 abnormal_event）
 *                     ↘ CANCELLED（计划暂停/修改时同步取消）
 * 幂等：uk_plantime_scheduled 唯一键保证「同一规则同一时刻」只生成一条任务。
 */
@Data
@TableName("remind_task")
public class RemindTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属计划 */
    private Long planId;

    /** 来源时间点规则 */
    private Long planTimeId;

    /** 冗余归属用户（按人查当天任务免联表） */
    private Long userId;

    /** 计划触发时间 */
    private LocalDateTime scheduledAt;

    /** 容忍窗口截止时间（超此即判超时） */
    private LocalDateTime expireAt;

    /** WAITING/TRIGGERED/CONFIRMED/EXPIRED/CANCELLED */
    private String status;

    /** 已触发次数（防止一次提醒反复通知） */
    private Integer triggerCount;

    /** 最近触发时间 */
    private LocalDateTime lastTriggerAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
