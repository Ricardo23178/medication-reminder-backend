package com.medreminder.medicationbackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当天提醒清单条目（VO）。
 * 相比 remind_task 实体，多了药品名（medicineName）、剂量（dosagePerTime/dosageUnit），
 * 前端一次拿到「几点吃什么、吃多少」，不用再查药品。
 */
@Data
public class RemindTaskVO {

    private Long id;

    /** 计划ID（跳转计划详情用） */
    private Long planId;

    /** 药品名 */
    private String medicineName;

    /** 单次服用量 */
    private java.math.BigDecimal dosagePerTime;

    /** 服用量单位 */
    private String dosageUnit;

    /** 0=无关 1=餐前 2=餐后 3=晨起空腹 4=睡前 */
    private Integer mealRelation;

    /** 计划触发时间 */
    private LocalDateTime scheduledAt;

    /** 容忍窗口截止时间 */
    private LocalDateTime expireAt;

    /** WAITING/TRIGGERED/CONFIRMED/EXPIRED/CANCELLED */
    private String status;

    /** 是否已确认（前端据此渲染「已服」状态） */
    private Boolean confirmed;

    /** 若已确认，这里给执行动作 TAKEN/DELAYED/SKIPPED */
    private String action;
}
