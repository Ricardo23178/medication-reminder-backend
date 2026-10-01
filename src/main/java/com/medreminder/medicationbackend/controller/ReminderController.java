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

    /**
     * 手动触发一次「为所有 ACTIVE 计划生成今天任务」（测试/演示用）。
     * 正常情况下由定时任务自动跑；这个口子让你在 Apifox 里点一下就能立刻看到闭环效果，
     * 不用等定时器或真实时间点。幂等，重复点不会产生脏数据。
     */
    @PostMapping("/generate-today")
    public Result<String> generateToday() {
        reminderService.generateTodayTasksForAll();
        return Result.ok("已生成当天提醒任务");
    }

    /**
     * 手动触发一次「扫描超时未确认任务，生成异常事件」（测试/演示用）。
     * 正常情况下由定时任务自动跑；这个口子让你在 Apifox 里点一下就能立刻验证
     * 「超时 → 生成异常事件 → 家属可查」的完整链路，不用等定时器或真实超时时间点。
     * 幂等，重复点不会产生重复异常（uk_task_type 唯一键兜底）。
     */
    @PostMapping("/scan-expired")
    public Result<String> scanExpired() {
        reminderService.scanExpiredTasks();
        return Result.ok("已扫描超时任务并生成异常事件");
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
