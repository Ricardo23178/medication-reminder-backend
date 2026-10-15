package com.medreminder.medicationbackend;

import com.medreminder.medicationbackend.entity.*;
import com.medreminder.medicationbackend.mapper.*;
import com.medreminder.medicationbackend.util.JwtUtil;
import com.medreminder.medicationbackend.service.NotificationService;
import com.medreminder.medicationbackend.service.NotificationDeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in real MySQL verification. Every fixture and API write rolls back with its test. */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(properties = {"reminder.scheduling.enabled=false", "notification.delivery.enabled=false",
        "app.initialize-admin=false"})
@AutoConfigureMockMvc
@Transactional
class CollaborationApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired UserMapper users;
    @Autowired NotificationMapper notifications;
    @Autowired AbnormalEventMapper events;
    @Autowired FamilyRelationMapper relations;
    @Autowired MedicineMapper medicines;
    @Autowired PlanMapper plans;
    @Autowired PlanTimeMapper times;
    @Autowired RemindTaskMapper tasks;
    @Autowired NotificationService inbox;
    @Autowired NotificationDeliveryService delivery;

    @Test
    void eventFanoutIsIdempotentAndExcludesLateOrInsufficientGrants() {
        Long owner = user(), family = user(), planOnly = user(), late = user();
        AbnormalEvent event = event(owner);
        relation(owner, family, "PLAN_STATUS,ABNORMAL_EVENT");
        relation(owner, planOnly, "PLAN_STATUS");
        FamilyRelation lateRelation = relation(owner, late, "ABNORMAL_EVENT");
        lateRelation.setBindTime(LocalDateTime.now());
        relations.updateById(lateRelation);
        inbox.queueAbnormalEvent(event);
        inbox.queueAbnormalEvent(event);
        var messages = notifications.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                .eq(Notification::getBizType, "ABNORMAL_EVENT").eq(Notification::getBizId, event.getId()));
        assertEquals(2, messages.size());
        assertEquals(java.util.Set.of(owner, family), messages.stream().map(Notification::getReceiverId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(messages.stream().allMatch(n -> "PENDING".equals(n.getSendStatus())));
    }

    @Test
    void publicationMarksNotifiedOnlyForFamilyAndNeverRewindsAcknowledgement() {
        Long owner = user(), family = user();
        AbnormalEvent event = event(owner);
        relation(owner, family, "ABNORMAL_EVENT");
        Notification own = message(owner, "PENDING", event.getId());
        Notification familyMessage = message(family, "PENDING", event.getId());
        delivery.publish(own.getId());
        assertEquals("OPEN", events.selectById(event.getId()).getStatus());
        delivery.publish(familyMessage.getId());
        assertEquals("NOTIFIED", events.selectById(event.getId()).getStatus());
        assertNotNull(events.selectById(event.getId()).getNotifiedAt());
        delivery.publish(familyMessage.getId());
        assertEquals("SENT", notifications.selectById(familyMessage.getId()).getSendStatus());
        assertNotNull(notifications.selectById(familyMessage.getId()).getSentAt());
        event.setStatus("ACKNOWLEDGED");
        events.updateById(event);
        Notification another = message(family, "PENDING", event.getId());
        delivery.publish(another.getId());
        assertEquals("ACKNOWLEDGED", events.selectById(event.getId()).getStatus());
    }

    @Test
    void failedPublicationIsBoundedAndCanRecoverBeforeLimit() {
        Long receiver = user();
        Notification exhausted = message(receiver, "PENDING", null);
        for (int attempt = 0; attempt < 5; attempt++) delivery.recordFailure(exhausted.getId());
        assertEquals(3, notifications.selectById(exhausted.getId()).getRetryCount());
        delivery.publish(exhausted.getId());
        assertEquals("FAILED", notifications.selectById(exhausted.getId()).getSendStatus());
        Notification recovered = message(receiver, "PENDING", null);
        delivery.recordFailure(recovered.getId());
        delivery.publish(recovered.getId());
        assertEquals("SENT", notifications.selectById(recovered.getId()).getSendStatus());
        assertEquals(1, notifications.selectById(recovered.getId()).getRetryCount());
    }

    @Test
    void revokedPendingFamilyNotificationIsCancelled() {
        Long owner = user(), family = user();
        AbnormalEvent event = event(owner);
        FamilyRelation relation = relation(owner, family, "ABNORMAL_EVENT");
        Notification message = message(family, "PENDING", event.getId());
        relation.setRelationStatus("REVOKED");
        relations.updateById(relation);
        delivery.publish(message.getId());
        assertEquals("CANCELLED", notifications.selectById(message.getId()).getSendStatus());
        assertEquals("OPEN", events.selectById(event.getId()).getStatus());
    }

    @Test
    void inboxShowsOnlyPublishedOwnMessagesAndSupportsUnreadAndCursor() throws Exception {
        Long me = user(), other = user();
        Notification first = message(me, "SENT", null);
        Notification second = message(me, "SENT", null);
        message(me, "PENDING", null);
        message(other, "SENT", null);
        mvc.perform(get("/api/notifications").param("limit", "1").header("Authorization", token(me)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(2));
        mvc.perform(get("/api/notifications").param("beforeId", second.getId().toString())
                        .header("Authorization", token(me)))
                .andExpect(jsonPath("$.data.items[0].id").value(first.getId()));
    }

    @Test
    void readingIsIdempotentAndCannotReadAnotherUsersMessage() throws Exception {
        Long me = user(), other = user();
        Notification mine = message(me, "SENT", null), theirs = message(other, "SENT", null);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/notifications/{id}/read", mine.getId()).header("Authorization", token(me)))
                    .andExpect(jsonPath("$.code").value(200));
        }
        mvc.perform(post("/api/notifications/{id}/read", theirs.getId()).header("Authorization", token(me)))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, notifications.selectById(mine.getId()).getReadFlag());
        assertEquals(0, notifications.selectById(theirs.getId()).getReadFlag());
        mvc.perform(get("/api/notifications").param("unreadOnly", "true").header("Authorization", token(me)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void readAllDoesNotModifyOtherUsersOrUnpublishedMessages() throws Exception {
        Long me = user(), other = user();
        Notification mine = message(me, "SENT", null), pending = message(me, "PENDING", null);
        Notification theirs = message(other, "SENT", null);
        mvc.perform(post("/api/notifications/read-all").header("Authorization", token(me)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1, notifications.selectById(mine.getId()).getReadFlag());
        assertEquals(0, notifications.selectById(pending.getId()).getReadFlag());
        assertEquals(0, notifications.selectById(theirs.getId()).getReadFlag());
    }

    @Test
    void revocationHidesPreviouslyPublishedFamilyMessagesAndRejectsActions() throws Exception {
        Long owner = user(), family = user();
        AbnormalEvent event = event(owner);
        FamilyRelation relation = relation(owner, family, "ABNORMAL_EVENT");
        Notification notification = message(family, "SENT", event.getId());
        mvc.perform(get("/api/notifications/{id}", notification.getId()).header("Authorization", token(family)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/family/relations/{id}", relation.getId()).header("Authorization", token(owner)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/notifications").header("Authorization", token(family)))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(get("/api/notifications/{id}", notification.getId()).header("Authorization", token(family)))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/family/abnormal-events/{id}/acknowledge", event.getId())
                        .header("Authorization", token(family)))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void acknowledgeAndClosePreserveFirstActorAndDoNotChangeMedicationStatus() throws Exception {
        Long owner = user(), family = user(), secondFamily = user();
        AbnormalEvent event = event(owner);
        relation(owner, family, "ABNORMAL_EVENT");
        relation(owner, secondFamily, "ABNORMAL_EVENT");
        mvc.perform(post("/api/family/abnormal-events/{id}/close", event.getId()).header("Authorization", token(family)))
                .andExpect(jsonPath("$.code").value(409));
        for (Long actor : new Long[]{family, secondFamily}) {
            mvc.perform(post("/api/family/abnormal-events/{id}/acknowledge", event.getId())
                            .header("Authorization", token(actor)))
                    .andExpect(jsonPath("$.code").value(200));
        }
        assertEquals(family, events.selectById(event.getId()).getAcknowledgedBy());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/family/abnormal-events/{id}/close", event.getId()).header("Authorization", token(family)))
                    .andExpect(jsonPath("$.code").value(200));
        }
        assertEquals("CLOSED", events.selectById(event.getId()).getStatus());
        assertNotNull(events.selectById(event.getId()).getClosedAt());
        assertEquals("EXPIRED", tasks.selectById(event.getTaskId()).getStatus());
        mvc.perform(get("/api/family/abnormal-events").header("Authorization", token(family)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/family/abnormal-events").param("includeClosed", "true")
                        .header("Authorization", token(family)))
                .andExpect(jsonPath("$.data[0].status").value("CLOSED"));
    }

    @Test
    void planOnlyGrantDoesNotAuthorizeNotificationsOrAcknowledgement() throws Exception {
        Long owner = user(), family = user();
        AbnormalEvent event = event(owner);
        relation(owner, family, "PLAN_STATUS");
        message(family, "SENT", event.getId());
        mvc.perform(get("/api/notifications").header("Authorization", token(family)))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(post("/api/family/abnormal-events/{id}/acknowledge", event.getId())
                        .header("Authorization", token(family)))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void newEndpointsRequireAuthenticationAndRejectUnboundedPageSizes() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").param("limit", "501").header("Authorization", token(user())))
                .andExpect(jsonPath("$.code").value(400));
    }

    Long user() {
        User user = new User();
        user.setAccount("it_" + UUID.randomUUID().toString().substring(0, 24));
        user.setNickname("集成测试");
        user.setPasswordHash("test-only-not-a-login-password");
        user.setRole("USER");
        user.setStatus(1);
        users.insert(user);
        return user.getId();
    }

    String token(Long userId) { return "Bearer " + jwt.generate(userId, "USER"); }

    Notification message(Long receiver, String status, Long eventId) {
        Notification notification = new Notification();
        notification.setReceiverId(receiver);
        notification.setType(eventId == null ? "SYSTEM" : "FAMILY_ALERT");
        notification.setTitle("测试消息");
        notification.setContent("测试正文");
        notification.setBizType(eventId == null ? null : "ABNORMAL_EVENT");
        notification.setBizId(eventId);
        notification.setReadFlag(0);
        notification.setSendStatus(status);
        notification.setRetryCount(0);
        notification.setCreateTime(LocalDateTime.now());
        notifications.insert(notification);
        return notification;
    }

    FamilyRelation relation(Long owner, Long family, String scope) {
        FamilyRelation relation = new FamilyRelation();
        relation.setOwnerUserId(owner);
        relation.setFamilyUserId(family);
        relation.setRelationStatus("ACTIVE");
        relation.setScope(scope);
        relation.setBindTime(LocalDateTime.now().minusDays(2));
        relations.insert(relation);
        return relation;
    }

    AbnormalEvent event(Long owner) {
        Medicine medicine = new Medicine();
        medicine.setUserId(owner);
        medicine.setName("测试药品");
        medicines.insert(medicine);
        MedicationPlan plan = new MedicationPlan();
        plan.setUserId(owner);
        plan.setMedicineId(medicine.getId());
        plan.setStartDate(LocalDate.now().minusDays(1));
        plans.insert(plan);
        PlanTime time = new PlanTime();
        time.setPlanId(plan.getId());
        time.setRemindTime(java.time.LocalTime.NOON);
        times.insert(time);
        RemindTask task = new RemindTask();
        task.setUserId(owner);
        task.setPlanId(plan.getId());
        task.setPlanTimeId(time.getId());
        task.setScheduledAt(LocalDateTime.now().minusHours(4));
        task.setExpireAt(LocalDateTime.now().minusHours(2));
        task.setStatus("EXPIRED");
        tasks.insert(task);
        AbnormalEvent event = new AbnormalEvent();
        event.setUserId(owner);
        event.setTaskId(task.getId());
        event.setType("UNCONFIRMED_TIMEOUT");
        event.setStatus("OPEN");
        event.setOccurredAt(task.getExpireAt());
        events.insert(event);
        return event;
    }
}
