package com.medreminder.medicationbackend.config;

import com.medreminder.medicationbackend.service.ReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 提醒任务定时调度器 —— 服药闭环的「发动机」。
 *
 * 职责：每隔固定时间扫描一次全库所有 ACTIVE 计划，
 * 提前把「今天每一剂」的 remind_task 实例生成出来，
 * 让「提醒」独立于患者是否打开 App —— 这是从「半自动」到「自动闭环」的关键一步。
 *
 * 为什么用轮询而不是算准每个时间点触发：
 * - 学生毕设 + 单机部署，轮询最简单可靠，无需引入 Quartz / 分布式锁（导师红线禁止 Quartz）。
 * - 幂等由 remind_task 的 uk_plantime_scheduled 唯一键兜底，重复生成无副作用。
 *
 * 触发频率说明（答辩可讲）：
 * - 每 5 分钟跑一次，保证「新建计划后最多 5 分钟」就能看到当天任务；
 * - 跨天时自动补生成新一天的任务（凌晨 00:05 那一轮扫到新日期）。
 */
@Component
@ConditionalOnProperty(name = "reminder.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;

    public ReminderScheduler(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 每 5 分钟执行一次，从启动后第 1 秒开始计时（fixedDelay 避免并发重入）。
     * cron 写法等价：0 0/5 * * * ?
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 1_000)
    public void generateDailyTasks() {
        try {
            reminderService.generateTodayTasksForAll();
        } catch (Exception e) {
            // 定时任务内必须吞掉异常，否则一次报错会让后续调度停止
            log.error("[定时任务] 生成当天提醒任务失败", e);
        }
    }

    /**
     * 每 5 分钟扫描一次「超时未确认」的任务，置 EXPIRED 并生成异常事件。
     * 与生成任务错开初始延迟（10 秒），避免同一时刻大量操作。
     * 幂等靠 abnormal_event 的 uk_task_type 唯一键兜底。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 10_000)
    public void scanExpiredTasks() {
        try {
            reminderService.scanExpiredTasks();
        } catch (Exception e) {
            log.error("[定时任务] 扫描超时未确认任务失败", e);
        }
    }
}
