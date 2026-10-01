package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 notification 表（消息中心与通知记录）。
 * 可靠性落地：业务事件先落库（abnormal_event）→ 再生成通知记录 → 发送结果回写。
 * send_status / retry_count 让「发送失败也能追查、可补发」—— 导师方案第十一节第 6 条。
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long receiverId;

    /** REMIND用药提醒/FAMILY_ALERT家属告警/SYSTEM系统消息 */
    private String type;

    private String title;

    /** 正文（不得写入敏感明文） */
    private String content;

    /** 关联业务类型：REMIND_TASK/ABNORMAL_EVENT */
    private String bizType;

    /** 关联业务ID（点击跳转用） */
    private Long bizId;

    /** 0=未读 1=已读 */
    private Integer readFlag;

    /** PENDING待发送/SENT已发送/FAILED发送失败 */
    private String sendStatus;

    /** 重试次数 */
    private Integer retryCount;

    /** 发送成功时间 */
    private LocalDateTime sentAt;

    private LocalDateTime createTime;
}
