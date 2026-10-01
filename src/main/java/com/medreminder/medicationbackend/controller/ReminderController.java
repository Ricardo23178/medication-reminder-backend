package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.dto.ConfirmRequest;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.service.ReminderService;
import com.medreminder.medicationbackend.vo.RemindTaskVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提醒任务接口 —— 导师方案接口清单第 5、6 个：reminders/today、reminders/{id}/confirm。
 *   GET  /api/reminders/today        查当天提醒清单（含药品名、剂量、状态）
 *   POST /api/reminders/{id}/confirm 确认服药（幂等：重复点击返回首次结果）
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /** 查当天提醒清单 */
    @GetMapping("/today")
    public Result<List<RemindTaskVO>> today() {
        return Result.ok(reminderService.listToday(UserContext.userId()));
    }

    /** 确认服药（幂等） */
    @PostMapping("/{id}/confirm")
    public Result<RemindTaskVO> confirm(@PathVariable Long id, @Valid @RequestBody ConfirmRequest req) {
        Long userId = UserContext.userId();
        int code = reminderService.confirm(userId, id, req);

        switch (code) {
            case 0 -> {
                // 首次确认成功，回查该任务最新状态返回
                return Result.ok(reminderService.getTaskVO(userId, id));
            }
            case 1 -> {
                // 幂等命中：已确认过，返回首次结果
                RemindTaskVO vo = reminderService.getTaskVO(userId, id);
                return Result.ok(vo); // 幂等返回 200，不报错
            }
            case 3 -> {
                return Result.error(400, "提醒已超时，无法确认");
            }
            default -> {
                return Result.error(403, "提醒任务不存在或无权操作");
            }
        }
    }
}
