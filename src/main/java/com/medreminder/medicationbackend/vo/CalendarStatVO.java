package com.medreminder.medicationbackend.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 用药日历与状态统计返回体。
 * 结构：按日期分组，每天一条 DayStat，附一个整体汇总 Summary。
 *
 * 统计口径（导师方案第九节）：
 *   - 「应完成」以 remind_task 为准（当天计划生成的任务实例数）；
 *   - 「已服 / 稍后 / 跳过」来自 medication_record 的 action；
 *   - 「未确认」= 应完成 - 已服 - 稍后 - 跳过（含超时与仍在等待的）。
 *   - 完成率 = (已服 + 稍后) / 应完成（稍后视作「已完成」，因为用户做了响应）。
 */
@Data
public class CalendarStatVO {

    private LocalDate startDate;
    private LocalDate endDate;

    /** 按天的统计明细（升序） */
    private List<DayStat> days;

    /** 整体汇总 */
    private Summary summary;

    @Data
    public static class DayStat {
        /** 日期 */
        private LocalDate date;
        /** 当天应完成的任务数（该吃几剂） */
        private int shouldCount;
        /** 已服 */
        private int takenCount;
        /** 稍后（顺延，视作已响应） */
        private int delayedCount;
        /** 跳过 */
        private int skippedCount;
        /** 未确认（超时或仍在等待） */
        private int unconfirmedCount;
        /** 完成率（0~100，保留 1 位小数） */
        private double completionRate;
    }

    @Data
    public static class Summary {
        private int totalShould;
        private int totalTaken;
        private int totalDelayed;
        private int totalSkipped;
        private int totalUnconfirmed;
        /** 整体完成率 */
        private double completionRate;
    }
}
