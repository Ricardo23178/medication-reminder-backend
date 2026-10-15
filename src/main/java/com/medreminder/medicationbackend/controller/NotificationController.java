package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.service.NotificationService;
import com.medreminder.medicationbackend.vo.NotificationPageVO;
import com.medreminder.medicationbackend.vo.NotificationVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public Result<NotificationPageVO> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(service.list(UserContext.userId(), unreadOnly, beforeId, limit));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() { return Result.ok(service.unreadCount(UserContext.userId())); }

    @GetMapping("/{id}")
    public Result<NotificationVO> detail(@PathVariable Long id) {
        NotificationVO message = service.get(UserContext.userId(), id);
        return message == null ? Result.error(403, "消息不存在或授权已失效") : Result.ok(message);
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        return service.read(UserContext.userId(), id) ? Result.ok(null)
                : Result.error(403, "消息不存在或授权已失效");
    }

    @PostMapping("/read-all")
    public Result<Integer> readAll() { return Result.ok(service.readAll(UserContext.userId())); }
}
