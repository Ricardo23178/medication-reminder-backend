package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 abnormal_event 表（超时/未确认等异常事件）。
 * 状态机：OPEN（已创建）→ NOTIFIED（已通知家属）→ ACKNOWLEDGED（家属已知晓）→ CLOSED。
 * 口径澄清：系统只判断「规定窗口内是否完成确认」，不宣称医学意义上的真实漏服，
 * 因此类型用 UNCONFIRMED（未确认）而非 MISSED（漏服）。
 * 防重复告警：uk_task_type 唯一键，同一任务同一类异常只建一条。
 */
@Data
@TableName("abnormal_event")
public class AbnormalEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 关联提醒任务 */
    private Long taskId;

    /** UNCONFIRMED_TIMEOUT/CONSECUTIVE_UNCONFIRMED/PLAN_ENDED_UNFINISHED */
    private String type;

    /** OPEN/NOTIFIED/ACKNOWLEDGED/CLOSED */
    private String status;

    /** 事件发生时间（判定时刻） */
    private LocalDateTime occurredAt;

    /** 通知发出时间 */
    private LocalDateTime notifiedAt;

    /** 确认知晓的家属用户 */
    private Long acknowledgedBy;

    /** 知晓时间 */
    private LocalDateTime acknowledgedAt;

    /** 事件关闭时间 */
    private LocalDateTime closedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
