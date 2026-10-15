package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.Notification;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NotificationDispatcherTest {
    final NotificationMapper notifications = mock(NotificationMapper.class);
    final AbnormalEventMapper events = mock(AbnormalEventMapper.class);
    final NotificationService inbox = mock(NotificationService.class);
    final NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
    final NotificationDispatcher dispatcher = new NotificationDispatcher(notifications, events, inbox, delivery);

    @Test
    void publicationFailureRecordsAnAttemptAndContinuesWithNextMessage() {
        when(notifications.selectList(any(Wrapper.class))).thenReturn(List.of(message(1L), message(2L)));
        doThrow(new TransientDataAccessResourceException("simulated database outage"))
                .when(delivery).publish(1L);
        dispatcher.dispatchPending();
        verify(delivery).recordFailure(1L);
        verify(delivery).publish(2L);
        verify(delivery, never()).recordFailure(2L);
    }

    @Test
    void failureToRecordAnAttemptStillDoesNotBlockOtherMessages() {
        when(notifications.selectList(any(Wrapper.class))).thenReturn(List.of(message(1L), message(2L)));
        doThrow(new TransientDataAccessResourceException("simulated outage")).when(delivery).publish(1L);
        doThrow(new TransientDataAccessResourceException("still unavailable")).when(delivery).recordFailure(1L);
        dispatcher.dispatchPending();
        verify(delivery).publish(2L);
    }

    @Test
    void legacyOwnerOutboxPreparesFamilyNotificationsBeforePublication() {
        Notification legacy = message(1L);
        legacy.setBizType("ABNORMAL_EVENT");
        legacy.setBizId(9L);
        AbnormalEvent event = new AbnormalEvent();
        event.setId(9L);
        event.setUserId(legacy.getReceiverId());
        when(events.selectById(9L)).thenReturn(event);
        when(notifications.selectList(any(Wrapper.class))).thenReturn(List.of(legacy));
        dispatcher.dispatchPending();
        var order = inOrder(inbox, delivery);
        order.verify(inbox).queueAbnormalEvent(event);
        order.verify(delivery).publish(1L);
    }

    @Test
    void fanoutFailureCountsAsFailedAttemptAndDoesNotPublishIncompleteMessage() {
        Notification legacy = message(1L);
        legacy.setBizType("ABNORMAL_EVENT");
        legacy.setBizId(9L);
        AbnormalEvent event = new AbnormalEvent();
        event.setId(9L);
        event.setUserId(legacy.getReceiverId());
        when(events.selectById(9L)).thenReturn(event);
        when(notifications.selectList(any(Wrapper.class))).thenReturn(List.of(legacy));
        doThrow(new TransientDataAccessResourceException("fanout failed"))
                .when(inbox).queueAbnormalEvent(event);
        dispatcher.dispatchPending();
        verify(delivery).recordFailure(1L);
        verify(delivery, never()).publish(1L);
    }

    private Notification message(Long id) {
        Notification result = new Notification();
        result.setId(id);
        result.setReceiverId(3L);
        result.setType("SYSTEM");
        result.setSendStatus("PENDING");
        result.setRetryCount(0);
        return result;
    }
}
