package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.Notification;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** SENT仅表示已发布到站内消息中心，不表示设备收到系统推送或家属已读。 */
@Service
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final NotificationMapper notifications;
    private final AbnormalEventMapper events;
    private final NotificationService service;
    private final NotificationDeliveryService delivery;

    public NotificationDispatcher(NotificationMapper notifications, AbnormalEventMapper events,
                                  NotificationService service, NotificationDeliveryService delivery) {
        this.notifications = notifications;
        this.events = events;
        this.service = service;
        this.delivery = delivery;
    }

    public void dispatchPending() {
        for (Notification message : notifications.selectList(new LambdaQueryWrapper<Notification>()
                .in(Notification::getSendStatus, "PENDING", "FAILED")
                .lt(Notification::getRetryCount, NotificationDeliveryService.MAX_ATTEMPTS)
                .orderByAsc(Notification::getId).last("LIMIT 100"))) {
            try {
                // 兼容上一阶段只有本人待发送消息的存量数据；家属资格按事件发生时刻检查。
                if ("ABNORMAL_EVENT".equals(message.getBizType()) && message.getBizId() != null) {
                    AbnormalEvent event = events.selectById(message.getBizId());
                    if (event != null && event.getUserId().equals(message.getReceiverId())) {
                        service.queueAbnormalEvent(event);
                    }
                }
                deliver(message.getId());
            } catch (RuntimeException failure) {
                // 单条失败不阻断其他消息；数据库不可用时保留原待发送状态，下轮再取。
                log.warn("消息投递暂未完成，messageId={}，原因={}", message.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    public void deliver(Long messageId) {
        try {
            delivery.publish(messageId);
        } catch (RuntimeException failure) {
            delivery.recordFailure(messageId);
            throw failure;
        }
    }
}
