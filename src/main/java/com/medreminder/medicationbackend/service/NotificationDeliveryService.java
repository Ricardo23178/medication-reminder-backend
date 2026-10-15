package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.Notification;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 每条消息在独立的服务调用事务中发布，失败后由调度器在新调用中记录重试。 */
@Service
public class NotificationDeliveryService {
    public static final int MAX_ATTEMPTS = 3;
    private final NotificationMapper notifications;
    private final AbnormalEventMapper events;

    public NotificationDeliveryService(NotificationMapper notifications, AbnormalEventMapper events) {
        this.notifications = notifications;
        this.events = events;
    }

    @Transactional
    public void publish(Long id) {
        Notification message = lock(id);
        if (!pending(message)) return;
        AbnormalEvent event = "ABNORMAL_EVENT".equals(message.getBizType()) && message.getBizId() != null
                ? events.selectById(message.getBizId()) : null;
        boolean family = event != null && !event.getUserId().equals(message.getReceiverId());
        if (notifications.countAuthorized(message.getReceiverId(), id) == 0
                || (family && "CLOSED".equals(event.getStatus()))) {
            notifications.update(null, new LambdaUpdateWrapper<Notification>()
                    .eq(Notification::getId, id).set(Notification::getSendStatus, "CANCELLED"));
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        notifications.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id).set(Notification::getSendStatus, "SENT")
                .set(Notification::getSentAt, now));
        if (family) {
            events.update(null, new LambdaUpdateWrapper<AbnormalEvent>()
                    .eq(AbnormalEvent::getId, event.getId()).eq(AbnormalEvent::getStatus, "OPEN")
                    .set(AbnormalEvent::getStatus, "NOTIFIED").set(AbnormalEvent::getNotifiedAt, now));
        }
    }

    @Transactional
    public void recordFailure(Long id) {
        Notification message = lock(id);
        if (!pending(message)) return;
        notifications.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id).set(Notification::getSendStatus, "FAILED")
                .set(Notification::getRetryCount, message.getRetryCount() + 1));
    }

    private Notification lock(Long id) {
        return notifications.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, id).last("FOR UPDATE"));
    }

    private boolean pending(Notification message) {
        return message != null && ("PENDING".equals(message.getSendStatus()) || "FAILED".equals(message.getSendStatus()))
                && message.getRetryCount() != null && message.getRetryCount() < MAX_ATTEMPTS;
    }
}
