package com.medreminder.medicationbackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常事件展示对象。家属查看被授权对象的异常时返回。
 * 补了被照护方昵称、药品名、计划时间，家属一看就懂是谁、哪味药、什么异常。
 */
@Data
public class AbnormalEventVO {

    private Long id;

    /** 归属用户（被照护方） */
    private Long userId;
    private String userNickname;

    /** 关联提醒任务 */
    private Long taskId;

    /** UNCONFIRMED_TIMEOUT/CONSECUTIVE_UNCONFIRMED/PLAN_ENDED_UNFINISHED */
    private String type;

    /** OPEN/NOTIFIED/ACKNOWLEDGED/CLOSED */
    private String status;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;

    /** 药品名（方便家属识别） */
    private String medicineName;

    /** 计划触发时间 */
    private LocalDateTime scheduledAt;

    /** 家属已知晓时间 */
    private LocalDateTime acknowledgedAt;

    private LocalDateTime createTime;
}
