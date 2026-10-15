package com.medreminder.medicationbackend.vo;

import java.util.List;

public record NotificationPageVO(List<NotificationVO> items, long unreadCount,
                                 Long nextBeforeId, boolean hasMore) {}
