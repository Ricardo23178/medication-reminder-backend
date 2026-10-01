package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 medication_record 表（确认与执行记录）。
 * 幂等核心：uk_task 唯一键 —— 一个提醒任务最多一条记录。
 * 用户连续点「已服」或网络重试，第 2 次写入命中唯一键冲突，接口捕获后返回首次结果，
 * 不产生重复记录（幂等返回 200 而非报错）。
 */
@Data
@TableName("medication_record")
public class MedicationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联提醒任务 */
    private Long taskId;

    /** 确认人（数据主体） */
    private Long userId;

    /** TAKEN已服/DELAYED稍后/SKIPPED跳过/UNCONFIRMED超时未确认 */
    private String action;

    /** 实际操作时间（以服务端时间为准） */
    private LocalDateTime actionTime;

    /** 「稍后」顺延的分钟数 */
    private Integer delayMinutes;

    /** 「跳过」原因（用户自报，系统不作医学解释） */
    private String skipReason;

    /** APP/CARD卡片/SYSTEM系统判定/TASK */
    private String source;

    /** 客户端幂等键（弱网重传去重） */
    private String clientRequestId;

    private LocalDateTime createTime;
}
