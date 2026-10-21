package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medreminder.medicationbackend.dto.ConfirmRequest;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.entity.PlanTime;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 提醒任务业务 —— 系统「心脏」。
 *
 * 三大职责：
 * 1. 生成当天提醒任务：按 ACTIVE 计划 + 时间点，滚动生成当天「实例」（remind_task）。
 *    幂等靠 uk_plan_version_scheduled 唯一键 —— 同一版本同一时刻不重复生成。
 * 2. 查当天清单：返回今天该吃哪些药（带药品名、剂量、状态）。
 * 3. 已服/跳过：WAITING/TRIGGERED → CONFIRMED；稍后保持TRIGGERED，允许再次确认。
 *    幂等靠 uk_task 唯一键 —— 重复点击命中唯一键，返回首次结果，不报错、不重复记录。
 *
 * 设计说明（导师方案第十一节）：
 * - 按天滚动生成，查询按需补生成，定时器独立运行。
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
    private final AbnormalEventMapper abnormalEventMapper;
    private final NotificationService notificationService;

    public ReminderService(RemindTaskMapper remindTaskMapper,
                           MedicationRecordMapper recordMapper,
                           PlanMapper planMapper,
                           PlanTimeMapper planTimeMapper,
                           MedicineMapper medicineMapper,
                           AbnormalEventMapper abnormalEventMapper,
                           NotificationService notificationService) {
        this.remindTaskMapper = remindTaskMapper;
        this.recordMapper = recordMapper;
        this.planMapper = planMapper;
        this.planTimeMapper = planTimeMapper;
        this.medicineMapper = medicineMapper;
        this.abnormalEventMapper = abnormalEventMapper;
        this.notificationService = notificationService;
    }

    /**
     * 查当天提醒清单（含药品名）。
     * 查询前先「按需生成」当天任务，保证患者打开 App 就能看到今天该吃什么。
     */
    @Transactional
    public List<RemindTaskVO> listToday(Long userId) {
        ensureTodayTasks(userId);

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<RemindTask> tasks = remindTaskMapper.selectList(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getUserId, userId)
                        .and(w -> w.ge(RemindTask::getScheduledAt, today.atStartOfDay())
                                .lt(RemindTask::getScheduledAt, today.plusDays(1).atStartOfDay())
                                .or(previous -> previous.lt(RemindTask::getScheduledAt, today.atStartOfDay())
                                        .ge(RemindTask::getExpireAt, now)
                                        .in(RemindTask::getStatus, "WAITING", "TRIGGERED")))
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
     *   4 = 任务已取消或状态已变更
     */
    @Transactional
    public int confirm(Long userId, Long taskId, ConfirmRequest req) {
        // 先取得归属计划，再统一按“计划 → 任务”加锁，与改版、暂停和扫描保持一致。
        RemindTask candidate = remindTaskMapper.selectOne(new LambdaQueryWrapper<RemindTask>()
                .eq(RemindTask::getId, taskId).eq(RemindTask::getUserId, userId));
        if (candidate == null) {
            return 2;
        }
        MedicationPlan plan = lockOwnedPlan(candidate);
        RemindTask task = remindTaskMapper.selectOne(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getId, taskId)
                        .eq(RemindTask::getUserId, userId)
                        .last("FOR UPDATE"));
        if (task == null) {
            return 2;
        }

        // 幂等：已确认过，直接返回首次结果，不再重复落库
        if ("CONFIRMED".equals(task.getStatus())) {
            return 1;
        }
        if ("EXPIRED".equals(task.getStatus())) {
            return 3;
        }
        if (!"WAITING".equals(task.getStatus()) && !"TRIGGERED".equals(task.getStatus())) {
            return 4;
        }
        if (!isCurrentActiveVersion(plan, task)) {
            return 4;
        }

        // 超时校验：超出容忍窗口，不允许再确认
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(task.getExpireAt())) {
            return 3;
        }

        String action = validateAction(req.getAction());
        boolean delayed = "DELAYED".equals(action);
        int delayMinutes = req.getDelayMinutes() == null ? 15 : req.getDelayMinutes();
        if (delayed && (delayMinutes < 1 || delayMinutes > 120)) {
            throw new IllegalArgumentException("稍后时间必须为1至120分钟");
        }
        MedicationRecord existing = getRecordByTaskForUpdate(taskId);
        if (existing != null && req.getClientRequestId() != null && !req.getClientRequestId().isBlank()
                && req.getClientRequestId().equals(existing.getClientRequestId())) {
            return 1;
        }
        if (existing != null && !"DELAYED".equals(existing.getAction())) {
            return 1;
        }
        LambdaUpdateWrapper<RemindTask> transition = new LambdaUpdateWrapper<RemindTask>()
                .eq(RemindTask::getId, taskId).eq(RemindTask::getUserId, userId)
                .eq(RemindTask::getPlanVersion, plan.getPlanVersion())
                .in(RemindTask::getStatus, "WAITING", "TRIGGERED")
                .ge(RemindTask::getExpireAt, now)
                .set(RemindTask::getStatus, delayed ? "TRIGGERED" : "CONFIRMED");
        if (delayed) {
            transition.set(RemindTask::getLastTriggerAt, now.plusMinutes(delayMinutes))
                    .set(RemindTask::getExpireAt, now.plusMinutes(delayMinutes + CONFIRM_WINDOW_MINUTES));
        }
        int claimed = remindTaskMapper.update(null, transition);
        if (claimed == 0) {
            RemindTask current = remindTaskMapper.selectById(taskId);
            return current != null && "CONFIRMED".equals(current.getStatus()) ? 1 : 4;
        }

        // 写服药记录（唯一键 uk_task 兜底，万一并发重复点击也只会成功一条）
        MedicationRecord record = existing == null ? new MedicationRecord() : existing;
        record.setTaskId(taskId);
        record.setUserId(userId);
        record.setAction(action);
        record.setActionTime(now);
        record.setDelayMinutes(delayed ? delayMinutes : null);
        record.setSkipReason(req.getSkipReason());
        record.setSource("APP");
        record.setClientRequestId(req.getClientRequestId());
        try {
            if (existing == null) {
                recordMapper.insert(record);
            } else {
                recordMapper.updateById(record);
            }
        } catch (DuplicateKeyException e) {
            // 命中 uk_task：说明已有记录，幂等返回首次结果
            return 1;
        }

        task.setStatus(delayed ? "TRIGGERED" : "CONFIRMED");
        return 0;
    }

    /** 查某任务的当前确认结果（幂等命中时返回给前端展示） */
    public MedicationRecord getRecordByTask(Long taskId) {
        return recordMapper.selectOne(
                new LambdaQueryWrapper<MedicationRecord>()
                        .eq(MedicationRecord::getTaskId, taskId));
    }

    private MedicationRecord getRecordByTaskForUpdate(Long taskId) {
        // 与任务锁一致地读取最新结果，不能使用确认前建立的 REPEATABLE READ 快照。
        return recordMapper.selectOne(new LambdaQueryWrapper<MedicationRecord>()
                .eq(MedicationRecord::getTaskId, taskId).last("FOR UPDATE"));
    }

    /** 查单条任务 VO（按 id + user_id 越权过滤） */
    public RemindTaskVO getTaskVO(Long userId, Long taskId) {
        RemindTask task = remindTaskMapper.selectOne(
                new LambdaQueryWrapper<RemindTask>()
                        .eq(RemindTask::getId, taskId)
                        .eq(RemindTask::getUserId, userId));
        return task == null ? null : toVO(task);
    }

    /**
     * 为所有「执行中」计划生成当天提醒任务（定时器 + 手动触发共用）。
     * 这是服药闭环的「自动转起来」入口：不依赖患者打开 App，
     * 由后台定时扫描所有 ACTIVE 计划，提前把当天每一剂的任务实例生成好。
     * 幂等靠 uk_plan_version_scheduled 唯一键，重复调用不产生脏数据。
     */
    @Transactional
    public void generateTodayTasksForAll() {
        // 查全库所有 ACTIVE 计划（不分用户）
        List<MedicationPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<MedicationPlan>()
                        .eq(MedicationPlan::getStatus, "ACTIVE")
                        .orderByAsc(MedicationPlan::getId));

        for (MedicationPlan plan : plans) {
            ensureTodayTasksForPlan(plan);
        }
    }

    /**
     * 扫描所有「已超容忍窗口、但还没确认」的任务，判定为超时未确认：
     * 1. 任务状态 WAITING/TRIGGERED → 置为 EXPIRED；
     * 2. 生成一条 abnormal_event（type=UNCONFIRMED_TIMEOUT），供家属在协同模块查看；
     * 3. 给患者生成一条通知记录（FAMILY_ALERT 类告警的落点）。
     *
     * 幂等靠 uk_task_type 唯一键 —— 同一任务同一类异常只建一条，重复扫描无副作用。
     * 口径（导师红线）：只判「窗口内是否完成确认」，不宣称医学意义上的真实漏服，
     * 所以事件类型用 UNCONFIRMED（未确认），不是 MISSED（漏服）。
     */
    @Transactional
    public void scanExpiredTasks() {
        LocalDateTime now = LocalDateTime.now();

        // 找出「到期时间已过、但仍处于未确认状态」的任务
        List<RemindTask> expired = remindTaskMapper.selectList(
                new LambdaQueryWrapper<RemindTask>()
                        .lt(RemindTask::getExpireAt, now)
                        .in(RemindTask::getStatus, "WAITING", "TRIGGERED")
                        .orderByAsc(RemindTask::getPlanId, RemindTask::getId));

        for (RemindTask task : expired) {
            MedicationPlan plan = lockOwnedPlan(task);
            if (!isCurrentActiveVersion(plan, task)) {
                remindTaskMapper.update(null, new LambdaUpdateWrapper<RemindTask>()
                        .eq(RemindTask::getId, task.getId())
                        .in(RemindTask::getStatus, "WAITING", "TRIGGERED")
                        .set(RemindTask::getStatus, "CANCELLED"));
                continue;
            }
            // 1) 任务状态 → EXPIRED
            int claimed = remindTaskMapper.update(null, new LambdaUpdateWrapper<RemindTask>()
                    .eq(RemindTask::getId, task.getId())
                    .eq(RemindTask::getPlanVersion, plan.getPlanVersion())
                    .in(RemindTask::getStatus, "WAITING", "TRIGGERED")
                    .lt(RemindTask::getExpireAt, now)
                    .set(RemindTask::getStatus, "EXPIRED"));
            if (claimed == 0) {
                continue;
            }
            // 超时也是记录状态；此前的“稍后”不能永久保留成已响应结果。
            MedicationRecord record = getRecordByTaskForUpdate(task.getId());
            boolean newRecord = record == null;
            if (newRecord) {
                record = new MedicationRecord();
                record.setTaskId(task.getId());
                record.setUserId(task.getUserId());
            }
            record.setAction("UNCONFIRMED");
            record.setActionTime(task.getExpireAt());
            record.setSource("SYSTEM");
            if (newRecord) {
                recordMapper.insert(record);
            } else {
                recordMapper.updateById(record);
            }

            // 2) 生成异常事件（唯一键 uk_task_type 兜底，已存在则跳过）
            AbnormalEvent event = new AbnormalEvent();
            event.setUserId(task.getUserId());
            event.setTaskId(task.getId());
            event.setType("UNCONFIRMED_TIMEOUT");
            event.setStatus("OPEN");
            event.setOccurredAt(task.getExpireAt());
            try {
                abnormalEventMapper.insert(event);
            } catch (DuplicateKeyException e) {
                // 已存在同类异常，跳过
                continue;
            }

            // 与事件同事务入队，包含本人以及事件发生时已授权的家属。
            notificationService.queueAbnormalEvent(event);
        }
    }

    // ==================== 内部方法 ====================

    private MedicationPlan lockOwnedPlan(RemindTask task) {
        return planMapper.selectOne(new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getId, task.getPlanId())
                .eq(MedicationPlan::getUserId, task.getUserId()).last("FOR UPDATE"));
    }

    private boolean isCurrentActiveVersion(MedicationPlan plan, RemindTask task) {
        return plan != null && "ACTIVE".equals(plan.getStatus())
                && task.getPlanVersion() != null && Objects.equals(plan.getPlanVersion(), task.getPlanVersion());
    }

    /**
     * 按需生成当天任务。
     * 只处理「今天在计划起止日期范围内、状态 ACTIVE」的计划；
     * 每个启用时间点生成一条 remind_task（若尚未生成）。
     * 幂等：uk_plan_version_scheduled 唯一键，重复生成命中冲突即忽略。
     */
    private void ensureTodayTasks(Long userId) {
        List<MedicationPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<MedicationPlan>()
                        .eq(MedicationPlan::getUserId, userId)
                        .eq(MedicationPlan::getStatus, "ACTIVE")
                        .orderByAsc(MedicationPlan::getId));

        for (MedicationPlan plan : plans) {
            ensureTodayTasksForPlan(plan);
        }
    }

    /** 为单个计划生成当天任务（起止日期过滤 + 逐个时间点生成，幂等） */
    private void ensureTodayTasksForPlan(MedicationPlan plan) {
        // 与计划暂停、修改共享计划行锁，避免扫描旧计划后又插入新任务。
        plan = planMapper.selectOne(new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getId, plan.getId()).last("FOR UPDATE"));
        if (plan == null || !"ACTIVE".equals(plan.getStatus())) {
            return;
        }
        LocalDate today = LocalDate.now();

        // 起止日期过滤：今天不在计划范围内则跳过
        if (plan.getStartDate() != null && today.isBefore(plan.getStartDate())) {
            return;
        }
        if (plan.getEndDate() != null && today.isAfter(plan.getEndDate())) {
            return;
        }

        // 查该计划的启用时间点
        List<PlanTime> times = planTimeMapper.selectList(
                new LambdaQueryWrapper<PlanTime>()
                        .eq(PlanTime::getPlanId, plan.getId())
                        .eq(PlanTime::getEnabled, 1)
                        .orderByAsc(PlanTime::getSequenceNo)
                        .last("FOR UPDATE"));

        for (PlanTime pt : times) {
            LocalDateTime scheduledAt = LocalDateTime.of(today, pt.getRemindTime());
            if (plan.getUpdateTime() != null && scheduledAt.isBefore(plan.getUpdateTime())) {
                continue;
            }
            generateTaskIfAbsent(plan, pt, scheduledAt);
        }
    }

    /** 生成单条任务（幂等：唯一键冲突则忽略） */
    private void generateTaskIfAbsent(MedicationPlan plan, PlanTime pt, LocalDateTime scheduledAt) {
        // 历史版本未知的旧规则任务已占用此时点，基线版本不能重复生成它。
        if (plan.getPlanVersion() == 1 && remindTaskMapper.selectCount(new LambdaQueryWrapper<RemindTask>()
                .eq(RemindTask::getPlanId, plan.getId()).eq(RemindTask::getPlanTimeId, pt.getId())
                .eq(RemindTask::getScheduledAt, scheduledAt).isNull(RemindTask::getPlanVersion)) > 0) {
            return;
        }
        RemindTask task = new RemindTask();
        task.setPlanId(plan.getId());
        task.setPlanVersion(plan.getPlanVersion());
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

    /** 非法操作不能被当作已服用。 */
    private String validateAction(String action) {
        if ("DELAYED".equals(action) || "SKIPPED".equals(action) || "TAKEN".equals(action)) {
            return action;
        }
        throw new IllegalArgumentException("操作类型必须为TAKEN、DELAYED或SKIPPED");
    }

    /** 组装 VO：补药品名 + 剂量 + 确认状态 */
    private RemindTaskVO toVO(RemindTask task) {
        RemindTaskVO vo = new RemindTaskVO();
        vo.setId(task.getId());
        vo.setPlanId(task.getPlanId());
        vo.setPlanVersion(task.getPlanVersion());
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

        // 包括稍后待确认和超时记录，供客户端展示当前操作状态。
        MedicationRecord record = getRecordByTask(task.getId());
        if (record != null) {
            vo.setAction(record.getAction());
        }

        return vo;
    }
}
