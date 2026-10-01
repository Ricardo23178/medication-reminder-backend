package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.dto.ConfirmRequest;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.entity.PlanTime;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.MedicationRecordMapper;
import com.medreminder.medicationbackend.mapper.MedicineMapper;
import com.medreminder.medicationbackend.mapper.PlanMapper;
import com.medreminder.medicationbackend.mapper.PlanTimeMapper;
import com.medreminder.medicationbackend.mapper.RemindTaskMapper;
import com.medreminder.medicationbackend.vo.RemindTaskVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提醒任务业务 —— 系统「心脏」。
 *
 * 三大职责：
 * 1. 生成当天提醒任务：按 ACTIVE 计划 + 时间点，滚动生成当天「实例」（remind_task）。
 *    幂等靠 uk_plantime_scheduled 唯一键 —— 重复生成命中唯一键冲突，忽略即可。
 * 2. 查当天清单：返回今天该吃哪些药（带药品名、剂量、状态）。
 * 3. 确认服药：TRIGGERED/WATTING → CONFIRMED，落一条 medication_record。
 *    幂等靠 uk_task 唯一键 —— 重复点击命中唯一键，返回首次结果，不报错、不重复记录。
 *
 * 设计说明（导师方案第十一节）：
 * - 不一次性生成未来一年任务，按天滚动生成（这里查当天时按需补生成，定时任务第二步再上）。
 * - 越权：任务按 user_id 过滤，确认前 findOwned 校验归属。
 */
@Service
public class ReminderService {

    /** 服药确认的容忍窗口：任务 scheduled_at 之后多少分钟内确认仍算「正常确认」 */
    private static final int CONFIRM_WINDOW_MINUTES = 120;

    private final RemindTaskMapper remindTaskMapper;
    private final MedicationRecordMapper recordMapper;
    private final PlanMapper planMapper;
    private final PlanTimeMapper planTimeMapper;
    private final MedicineMapper medicineMapper;

    public ReminderService(RemindTaskMapper remindTaskMapper,
                           MedicationRecordMapper recordMapper,
                           PlanMapper planMapper,
                           PlanTimeMapper planTimeMapper,
                           MedicineMapper medicineMapper) {
        this.remindTaskMapper = remindTaskMapper;
        this.recordMapper = recordMapper;
        this.planMapper = planMapper;
        this.planTimeMapper = planTimeMapper;
        this.medicineMapper = medicineMapper;
    }

    /**
     * 查当天提醒清单（含药品名）。
     * 查询前先「按需生成」当天任务，保证患者打开 App 就能看到今天该吃什么。
     */
    public List<RemindTaskVO> listToday(Long userId) {
        ensureTodayTasks(userId);

        LocalDate today = LocalDate.now();
        List<RemindTask> tasks = remindTaskMapper.selectList(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getUserId, userId)
                        .ge(RemindTask::getScheduledAt, today.atStartOfDay())
                        .lt(RemindTask::getScheduledAt, today.plusDays(1).atStartOfDay())
                        .orderByAsc(RemindTask::getScheduledAt));

        return tasks.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * 确认服药（幂等）。
     * 返回结果含义（用 Result 层区分，这里返回标记）：
     *   0 = 确认成功（首次）
     *   1 = 幂等命中（已确认过，返回首次结果）
     *   2 = 任务不存在或无权操作
     *   3 = 任务已过期（超出容忍窗口）
     */
    @Transactional
    public int confirm(Long userId, Long taskId, ConfirmRequest req) {
        // 越权校验：任务必须属于当前用户
        RemindTask task = remindTaskMapper.selectOne(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getId, taskId)
                        .eq(RemindTask::getUserId, userId));
        if (task == null) {
            return 2;
        }

        // 幂等：已确认过，直接返回首次结果，不再重复落库
        if ("CONFIRMED".equals(task.getStatus())) {
            return 1;
        }

        // 超时校验：超出容忍窗口，不允许再确认
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(task.getExpireAt())) {
            return 3;
        }

        String action = normalizeAction(req.getAction());

        // 写服药记录（唯一键 uk_task 兜底，万一并发重复点击也只会成功一条）
        MedicationRecord record = new MedicationRecord();
        record.setTaskId(taskId);
        record.setUserId(userId);
        record.setAction(action);
        record.setActionTime(now);
        record.setDelayMinutes(req.getDelayMinutes());
        record.setSkipReason(req.getSkipReason());
        record.setSource("APP");
        record.setClientRequestId(req.getClientRequestId());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 命中 uk_task：说明已有记录，幂等返回首次结果
            return 1;
        }

        // 任务状态流转 → CONFIRMED
        task.setStatus("CONFIRMED");
        remindTaskMapper.updateById(task);

        return 0;
    }

    /** 查某任务的当前确认结果（幂等命中时返回给前端展示） */
    public MedicationRecord getRecordByTask(Long taskId) {
        return recordMapper.selectOne(
                new LambdaQueryWrapper<MedicationRecord>()
                        .eq(MedicationRecord::getTaskId, taskId));
    }

    /** 查单条任务 VO（按 id + user_id 越权过滤） */
    public RemindTaskVO getTaskVO(Long userId, Long taskId) {
        RemindTask task = remindTaskMapper.selectOne(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getId, taskId)
                        .eq(RemindTask::getUserId, userId));
        return task == null ? null : toVO(task);
    }

    // ==================== 内部方法 ====================

    /**
     * 按需生成当天任务。
     * 只处理「今天在计划起止日期范围内、状态 ACTIVE」的计划；
     * 每个启用时间点生成一条 remind_task（若尚未生成）。
     * 幂等：uk_plantime_scheduled 唯一键，重复生成命中冲突即忽略。
     */
    private void ensureTodayTasks(Long userId) {
        LocalDate today = LocalDate.now();

        // 查当前用户所有「执行中」的计划
        List<MedicationPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<MedicationPlan>()
                        .eq(MedicationPlan::getUserId, userId)
                        .eq(MedicationPlan::getStatus, "ACTIVE"));

        for (MedicationPlan plan : plans) {
            // 起止日期过滤：今天不在计划范围内则跳过
            if (plan.getStartDate() != null && today.isBefore(plan.getStartDate())) {
                continue;
            }
            if (plan.getEndDate() != null && today.isAfter(plan.getEndDate())) {
                continue;
            }

            // 查该计划的启用时间点
            List<PlanTime> times = planTimeMapper.selectList(
                    new LambdaQueryWrapper<PlanTime>()
                            .eq(PlanTime::getPlanId, plan.getId())
                            .eq(PlanTime::getEnabled, 1)
                            .orderByAsc(PlanTime::getSequenceNo));

            for (PlanTime pt : times) {
                LocalDateTime scheduledAt = LocalDateTime.of(today, pt.getRemindTime());
                generateTaskIfAbsent(plan, pt, scheduledAt);
            }
        }
    }

    /** 生成单条任务（幂等：唯一键冲突则忽略） */
    private void generateTaskIfAbsent(MedicationPlan plan, PlanTime pt, LocalDateTime scheduledAt) {
        RemindTask task = new RemindTask();
        task.setPlanId(plan.getId());
        task.setPlanTimeId(pt.getId());
        task.setUserId(plan.getUserId());
        task.setScheduledAt(scheduledAt);
        task.setExpireAt(scheduledAt.plusMinutes(CONFIRM_WINDOW_MINUTES));
        task.setStatus("WAITING");
        task.setTriggerCount(0);
        try {
            remindTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 已存在，忽略（幂等）
        }
    }

    /** 动作归一化：非法值一律视为 TAKEN */
    private String normalizeAction(String action) {
        if ("DELAYED".equals(action) || "SKIPPED".equals(action) || "TAKEN".equals(action)) {
            return action;
        }
        return "TAKEN";
    }

    /** 组装 VO：补药品名 + 剂量 + 确认状态 */
    private RemindTaskVO toVO(RemindTask task) {
        RemindTaskVO vo = new RemindTaskVO();
        vo.setId(task.getId());
        vo.setPlanId(task.getPlanId());
        vo.setScheduledAt(task.getScheduledAt());
        vo.setExpireAt(task.getExpireAt());
        vo.setStatus(task.getStatus());
        vo.setConfirmed("CONFIRMED".equals(task.getStatus()));

        // 查计划 → 药品 + 剂量 + 餐时
        MedicationPlan plan = planMapper.selectById(task.getPlanId());
        if (plan != null) {
            vo.setDosagePerTime(plan.getDosagePerTime());
            vo.setDosageUnit(plan.getDosageUnit());
            vo.setMealRelation(plan.getMealRelation());

            Medicine medicine = medicineMapper.selectById(plan.getMedicineId());
            vo.setMedicineName(medicine == null ? null : medicine.getName());
        }

        // 已确认 → 回填执行动作
        if ("CONFIRMED".equals(task.getStatus())) {
            MedicationRecord record = getRecordByTask(task.getId());
            if (record != null) {
                vo.setAction(record.getAction());
            }
        }

        return vo;
    }
}
