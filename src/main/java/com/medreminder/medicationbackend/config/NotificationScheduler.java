package com.medreminder.medicationbackend.config;

import com.medreminder.medicationbackend.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private final NotificationDispatcher dispatcher;
    public NotificationScheduler(NotificationDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @Scheduled(fixedDelayString = "${notification.delivery.interval-ms:30000}", initialDelay = 15000)
    public void dispatch() {
        try { dispatcher.dispatchPending(); }
        catch (RuntimeException error) { log.warn("站内消息投递暂不可用，等待下轮重试", error); }
    }
}
