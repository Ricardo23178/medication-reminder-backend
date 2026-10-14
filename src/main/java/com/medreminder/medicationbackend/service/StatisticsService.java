package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.MedicationRecordMapper;
import com.medreminder.medicationbackend.mapper.RemindTaskMapper;
import com.medreminder.medicationbackend.vo.CalendarStatVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用药日历与状态统计 —— 导师方案接口清单第 10 个：GET /api/statistics/calendar。
 *
 * 统计口径（与导师第九节「仅作记录展示」一致，不做医学判断）：
 *   - 应完成：以 remind_task 为准，按 scheduled_at 的日期分组计数（当天该吃几剂）。
 *   - 已服/稍后/跳过：按任务原定日期，对 medication_record 的 action 分组。
 *   - 未确认：应完成 - 已服 - 跳过（含稍后待确认、超时 EXPIRED 和 WAITING）。
 *   - 完成率：已服 / 应完成；稍后是响应，不代表已经服药。取消任务不计入分母。
 *
 * 越权：所有查询都按 user_id 过滤，只能看自己的统计。
 */
@Service
public class StatisticsService {

    private final RemindTaskMapper taskMapper;
    private final MedicationRecordMapper recordMapper;

    public StatisticsService(RemindTaskMapper taskMapper,
                             MedicationRecordMapper recordMapper) {
        this.taskMapper = taskMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 查个人用药日历统计。
     * @param userId    当前用户
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     */
    public CalendarStatVO calendar(Long userId, LocalDate startDate, LocalDate endDate) {
        // 默认范围：最近 30 天
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(29);
        }
        if (startDate.isAfter(endDate) || java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) > 365) {
            throw new IllegalArgumentException("统计日期范围必须按先后顺序且不超过366天");
        }

        LocalDateTime from = startDate.atStartOfDay();
        LocalDateTime to = endDate.plusDays(1).atStartOfDay();

        // 1) 应完成：该范围内该用户的任务，按日期分组
        List<RemindTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getUserId, userId)
                        .ge(RemindTask::getScheduledAt, from)
                        .lt(RemindTask::getScheduledAt, to));
        tasks = tasks.stream().filter(t -> !"CANCELLED".equals(t.getStatus())).toList();

        Map<LocalDate, Integer> shouldMap = new HashMap<>();
        Map<Long, LocalDate> taskDates = new HashMap<>();
        for (RemindTask t : tasks) {
            LocalDate d = t.getScheduledAt().toLocalDate();
            shouldMap.merge(d, 1, Integer::sum);
            taskDates.put(t.getId(), d);
        }

        // 2) 按任务关联记录，允许午夜后的确认归入前一天的原定提醒。
        List<MedicationRecord> records = taskDates.isEmpty() ? List.of() : recordMapper.selectList(
                new LambdaQueryWrapper<MedicationRecord>()
                        .eq(MedicationRecord::getUserId, userId)
                        .in(MedicationRecord::getTaskId, taskDates.keySet()));

        // date -> action -> count
        Map<LocalDate, Map<String, Integer>> actionMap = new HashMap<>();
        for (MedicationRecord r : records) {
            LocalDate d = taskDates.get(r.getTaskId());
            if (d == null) {
                continue;
            }
            actionMap.computeIfAbsent(d, k -> new HashMap<>())
                    .merge(r.getAction(), 1, Integer::sum);
        }

        // 3) 组装按天明细
        CalendarStatVO vo = new CalendarStatVO();
        vo.setStartDate(startDate);
        vo.setEndDate(endDate);

        List<CalendarStatVO.DayStat> days = new ArrayList<>();
        int totalShould = 0, totalTaken = 0, totalDelayed = 0, totalSkipped = 0, totalUnconfirmed = 0;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            int should = shouldMap.getOrDefault(cursor, 0);
            Map<String, Integer> acts = actionMap.getOrDefault(cursor, new HashMap<>());
            int taken = acts.getOrDefault("TAKEN", 0);
            int delayed = acts.getOrDefault("DELAYED", 0);
            int skipped = acts.getOrDefault("SKIPPED", 0);
            int unconfirmed = Math.max(0, should - taken - skipped);
            double rate = should == 0 ? 0.0 : round1(taken * 100.0 / should);

            CalendarStatVO.DayStat day = new CalendarStatVO.DayStat();
            day.setDate(cursor);
            day.setShouldCount(should);
            day.setTakenCount(taken);
            day.setDelayedCount(delayed);
            day.setSkippedCount(skipped);
            day.setUnconfirmedCount(unconfirmed);
            day.setCompletionRate(rate);
            days.add(day);

            totalShould += should;
            totalTaken += taken;
            totalDelayed += delayed;
            totalSkipped += skipped;
            totalUnconfirmed += unconfirmed;

            cursor = cursor.plusDays(1);
        }

        vo.setDays(days);

        CalendarStatVO.Summary summary = new CalendarStatVO.Summary();
        summary.setTotalShould(totalShould);
        summary.setTotalTaken(totalTaken);
        summary.setTotalDelayed(totalDelayed);
        summary.setTotalSkipped(totalSkipped);
        summary.setTotalUnconfirmed(totalUnconfirmed);
        summary.setCompletionRate(totalShould == 0 ? 0.0 : round1(totalTaken * 100.0 / totalShould));
        vo.setSummary(summary);

        return vo;
    }

    /** 保留 1 位小数 */
    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
