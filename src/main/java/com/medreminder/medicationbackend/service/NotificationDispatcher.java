package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.Notification;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import com.medreminder.medicationbackend.admin.ConsoleSettings;
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
    private final ConsoleSettings settings;

    public NotificationDispatcher(NotificationMapper notifications, AbnormalEventMapper events,
                                  NotificationService service, NotificationDeliveryService delivery, ConsoleSettings settings) {
        this.notifications = notifications;
        this.events = events;
        this.service = service;
        this.delivery = delivery;
        this.settings = settings;
    }

    public void dispatchPending() {
        var policy = settings.deliveryPolicy();
        if (policy.paused()) return;
        for (Notification message : notifications.selectList(new LambdaQueryWrapper<Notification>()
                .in(Notification::getSendStatus, "PENDING", "FAILED")
                .lt(Notification::getRetryCount, NotificationDeliveryService.MAX_ATTEMPTS)
                .orderByAsc(Notification::getId).last("LIMIT " + policy.batchSize()))) {
            try {
                // 兼容上一阶段只有本人待发送消息的存量数据；家属资格按事件发生时刻检查。
                if ("ABNORMAL_EVENT".equals(message.getBizType()) && message.getBizId() != null) {
                    AbnormalEvent event = events.selectById(message.getBizId());
                    if (event != null && event.getUserId().equals(message.getReceiverId())) {
                        service.queueAbnormalEvent(event);
                    }
                }
                delivery.publish(message.getId());
            } catch (RuntimeException failure) {
                try { delivery.recordFailure(message.getId()); }
                catch (RuntimeException bookkeepingFailure) {
                    // 数据库完全不可用时，原待发送行仍保留，下轮恢复后继续处理。
                    log.warn("消息失败状态暂无法记录，messageId={}", message.getId());
                }
                // 单条失败不阻断其他消息。
                log.warn("消息投递暂未完成，messageId={}，原因={}", message.getId(), failure.getClass().getSimpleName());
            }
        }
    }

}
