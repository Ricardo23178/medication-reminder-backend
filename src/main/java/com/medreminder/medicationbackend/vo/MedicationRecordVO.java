package com.medreminder.medicationbackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服药记录展示对象。比实体多补了药品名，方便前端直接展示「哪味药、什么操作」。
 */
@Data
public class MedicationRecordVO {

    private Long id;

    /** 关联提醒任务 */
    private Long taskId;

    /** 药品名（后端关联查询补齐） */
    private String medicineName;

    /** TAKEN已服/DELAYED稍后/SKIPPED跳过/UNCONFIRMED超时未确认 */
    private String action;

    /** 实际操作时间 */
    private LocalDateTime actionTime;

    /** 「稍后」顺延的分钟数 */
    private Integer delayMinutes;

    /** 「跳过」原因 */
    private String skipReason;
}
