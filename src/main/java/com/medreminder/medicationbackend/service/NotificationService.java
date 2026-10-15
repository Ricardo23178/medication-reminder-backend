package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.FamilyRelation;
import com.medreminder.medicationbackend.entity.Notification;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.FamilyRelationMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import com.medreminder.medicationbackend.vo.NotificationPageVO;
import com.medreminder.medicationbackend.vo.NotificationVO;
import com.medreminder.medicationbackend.admin.ConsoleSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private final NotificationMapper notifications;
    private final AbnormalEventMapper events;
    private final FamilyRelationMapper relations;
    private final ConsoleSettings settings;

    public NotificationService(NotificationMapper notifications, AbnormalEventMapper events,
                               FamilyRelationMapper relations, ConsoleSettings settings) {
        this.notifications = notifications;
        this.events = events;
        this.relations = relations;
        this.settings = settings;
    }

    /** 与异常事件同事务入队；锁定事件串行化，避免重复扫描产生重复家属消息。 */
    @Transactional
    public void queueAbnormalEvent(AbnormalEvent source) {
        AbnormalEvent event = events.selectOne(new LambdaQueryWrapper<AbnormalEvent>()
                .eq(AbnormalEvent::getId, source.getId()).last("FOR UPDATE"));
        if (event == null || "CLOSED".equals(event.getStatus())) return;
        Set<Long> receivers = new HashSet<>();
        for (Notification existing : notifications.selectList(new LambdaQueryWrapper<Notification>()
                .select(Notification::getReceiverId)
                .eq(Notification::getBizType, "ABNORMAL_EVENT").eq(Notification::getBizId, event.getId()))) {
            receivers.add(existing.getReceiverId());
        }
        if (receivers.add(event.getUserId())) create(event, event.getUserId(), false, settings.template(false));
        ConsoleSettings.MessageTemplate familyTemplate = null;
        for (FamilyRelation relation : relations.selectList(new LambdaQueryWrapper<FamilyRelation>()
                .eq(FamilyRelation::getOwnerUserId, event.getUserId())
                .eq(FamilyRelation::getRelationStatus, "ACTIVE"))) {
            // 新绑定不补发绑定前的历史告警，也不向未授予异常权限的家属投递。
            if (relation.getFamilyUserId() != null && FamilyService.hasAbnormalScope(relation.getScope())
                    && relation.getBindTime() != null && !relation.getBindTime().isAfter(event.getOccurredAt())
                    && receivers.add(relation.getFamilyUserId())) {
                if (familyTemplate == null) familyTemplate = settings.template(true);
                create(event, relation.getFamilyUserId(), true, familyTemplate);
            }
        }
    }

    public NotificationPageVO list(Long userId, boolean unreadOnly, Long beforeId, int limit) {
        if (limit < 1 || limit > 50 || (beforeId != null && beforeId < 1)) {
            throw new IllegalArgumentException("每页数量应为1至50，消息游标必须为正数");
        }
        List<Notification> rows = notifications.selectVisible(userId, unreadOnly, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Notification> page = rows.stream().limit(limit).toList();
        List<Long> eventIds = page.stream().filter(n -> "ABNORMAL_EVENT".equals(n.getBizType()))
                .map(Notification::getBizId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, AbnormalEvent> pageEvents = eventIds.isEmpty() ? Map.of() : events.selectList(
                new LambdaQueryWrapper<AbnormalEvent>().select(AbnormalEvent::getId, AbnormalEvent::getUserId)
                        .in(AbnormalEvent::getId, eventIds)).stream()
                .collect(Collectors.toMap(AbnormalEvent::getId, Function.identity()));
        List<NotificationVO> items = page.stream().map(n -> toVO(userId, n,
                n.getBizId() == null ? null : pageEvents.get(n.getBizId()))).toList();
        Long next = hasMore ? items.get(items.size() - 1).getId() : null;
        return new NotificationPageVO(items, notifications.countVisibleUnread(userId), next, hasMore);
    }

    public long unreadCount(Long userId) { return notifications.countVisibleUnread(userId); }

    public NotificationVO get(Long userId, Long id) {
        Notification message = notifications.selectVisibleById(userId, id);
        if (message == null) return null;
        AbnormalEvent event = "ABNORMAL_EVENT".equals(message.getBizType()) && message.getBizId() != null
                ? events.selectById(message.getBizId()) : null;
        return toVO(userId, message, event);
    }

    public boolean read(Long userId, Long id) {
        // MySQL 默认返回匹配行数，已读消息重试仍成功；权限条件与查询完全一致。
        return notifications.markVisibleRead(userId, id) > 0
                || notifications.selectVisibleById(userId, id) != null;
    }

    public int readAll(Long userId) { return notifications.markAllVisibleRead(userId); }

    private void create(AbnormalEvent event, Long receiver, boolean family, ConsoleSettings.MessageTemplate template) {
        Notification message = new Notification();
        message.setReceiverId(receiver);
        message.setType(family ? "FAMILY_ALERT" : "SYSTEM");
        message.setTitle(template.title());
        message.setContent(template.content());
        message.setBizType("ABNORMAL_EVENT");
        message.setBizId(event.getId());
        message.setReadFlag(0);
        message.setSendStatus("PENDING");
        message.setRetryCount(0);
        message.setCreateTime(LocalDateTime.now());
        notifications.insert(message);
    }

    private NotificationVO toVO(Long userId, Notification message, AbnormalEvent event) {
        NotificationVO result = new NotificationVO();
        result.setId(message.getId());
        result.setType(message.getType());
        result.setTitle(message.getTitle());
        result.setContent(message.getContent());
        result.setBizType(message.getBizType());
        result.setBizId(message.getBizId());
        result.setReadFlag(message.getReadFlag());
        result.setCreateTime(message.getCreateTime());
        result.setFamilyEvent("ABNORMAL_EVENT".equals(message.getBizType())
                && event != null && !event.getUserId().equals(userId));
        return result;
    }
}
