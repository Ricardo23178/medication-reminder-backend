package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.RemindTaskMapper;
import com.medreminder.medicationbackend.dto.PlanRequest;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.entity.PlanTime;
import com.medreminder.medicationbackend.mapper.MedicineMapper;
import com.medreminder.medicationbackend.mapper.PlanMapper;
import com.medreminder.medicationbackend.mapper.PlanTimeMapper;
import com.medreminder.medicationbackend.vo.PlanVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用药计划业务。
 * 三个关键点：
 * 1. 一对多：一个计划关联多个时间点（plan_time），创建/修改时一起处理。
 * 2. 事务：计划主表 + 时间点必须同时成功或同时回滚，用 @Transactional。
 * 3. 越权：所有操作按 user_id 过滤，改/删/暂停前先 findOwned 校验归属。
 * 4. 状态机：DRAFT → ACTIVE ⇄ PAUSED → FINISHED / CANCELLED。
 */
@Service
public class PlanService {

    private final PlanMapper planMapper;
    private final PlanTimeMapper planTimeMapper;
    private final MedicineMapper medicineMapper;
    private final RemindTaskMapper taskMapper;

    public PlanService(PlanMapper planMapper, PlanTimeMapper planTimeMapper, MedicineMapper medicineMapper,
                       RemindTaskMapper taskMapper) {
        this.planMapper = planMapper;
        this.planTimeMapper = planTimeMapper;
        this.medicineMapper = medicineMapper;
        this.taskMapper = taskMapper;
    }

    /** 查当前用户的计划列表（含时间点和药品名） */
    public List<PlanVO> listByUser(Long userId) {
        List<MedicationPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<MedicationPlan>()
                        .eq(MedicationPlan::getUserId, userId)
                        .orderByDesc(MedicationPlan::getCreateTime));
        return plans.stream().map(this::toVO).collect(Collectors.toList());
    }

    /** 查单个计划详情（含越权校验），非本人返回 null */
    public PlanVO getOwned(Long userId, Long id) {
        MedicationPlan plan = findOwned(userId, id);
        return plan == null ? null : toVO(plan);
    }

    /**
     * 创建计划 + 时间点（事务）。
     * 校验：药品必须存在且属于本人。
     */
    @Transactional
    public PlanVO create(Long userId, PlanRequest req) {
        validateRequest(req, true);
        // 校验药品归属（防止拿别人的药建计划）
        Medicine medicine = medicineMapper.selectOne(
                new LambdaQueryWrapper<Medicine>()
                        .eq(Medicine::getId, req.getMedicineId())
                        .eq(Medicine::getUserId, userId));
        if (medicine == null) {
            throw new IllegalArgumentException("药品不存在或无权使用");
        }

        MedicationPlan plan = new MedicationPlan();
        plan.setUserId(userId);
        applyRequest(plan, req);
        plan.setStatus("ACTIVE"); // 新建直接进入执行中（方案 1 简化：不引入 DRAFT 草稿态）
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(plan.getCreateTime());
        planMapper.insert(plan);

        saveTimes(plan.getId(), req.getRemindTimes());
        return toVO(plan);
    }

    /** 修改计划（事务）。非本人返回 null。 */
    @Transactional
    public PlanVO update(Long userId, Long id, PlanRequest req) {
        MedicationPlan plan = findOwnedForUpdate(userId, id);
        if (plan == null) {
            return null;
        }
        validateRequest(req, false);
        Medicine medicine = medicineMapper.selectOne(new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getId, req.getMedicineId()).eq(Medicine::getUserId, userId));
        if (medicine == null) {
            throw new IllegalArgumentException("药品不存在或无权使用");
        }
        LocalDateTime effectiveAt = LocalDateTime.now();
        retireFutureTasks(userId, plan, effectiveAt);
        applyRequest(plan, req);
        plan.setPlanVersion(plan.getPlanVersion() + 1);
        plan.setUpdateTime(effectiveAt);
        planMapper.updateById(plan);

        // 时间点：先删旧再插新（简单可靠，避免比对差异）
        if (req.getRemindTimes() != null) {
            planTimeMapper.delete(new LambdaQueryWrapper<PlanTime>()
                    .eq(PlanTime::getPlanId, id));
            saveTimes(id, req.getRemindTimes());
        }
        return toVO(plan);
    }

    /** 暂停计划：ACTIVE → PAUSED。非本人或状态不对返回 null */
    @Transactional
    public PlanVO pause(Long userId, Long id) {
        MedicationPlan plan = findOwnedForUpdate(userId, id);
        if (plan == null || !"ACTIVE".equals(plan.getStatus())) {
            return null;
        }
        LocalDateTime effectiveAt = LocalDateTime.now();
        retireFutureTasks(userId, plan, effectiveAt);
        plan.setStatus("PAUSED");
        plan.setPlanVersion(plan.getPlanVersion() + 1);
        plan.setUpdateTime(effectiveAt);
        planMapper.updateById(plan);
        return toVO(plan);
    }

    /** 恢复计划：PAUSED → ACTIVE。非本人或状态不对返回 null */
    @Transactional
    public PlanVO resume(Long userId, Long id) {
        MedicationPlan plan = findOwnedForUpdate(userId, id);
        if (plan == null || !"PAUSED".equals(plan.getStatus())) {
            return null;
        }
        plan.setStatus("ACTIVE");
        plan.setPlanVersion(plan.getPlanVersion() + 1);
        plan.setUpdateTime(LocalDateTime.now());
        planMapper.updateById(plan);
        // 后续按新版本补生成未来任务，旧任务永久保持取消。
        return toVO(plan);
    }

    /** 删除计划（逻辑删除，同时逻辑删除时间点）。非本人返回 false */
    @Transactional
    public boolean delete(Long userId, Long id) {
        MedicationPlan plan = findOwnedForUpdate(userId, id);
        if (plan == null) {
            return false;
        }
        cancelPendingTasks(userId, id);
        planMapper.deleteById(id);
        // plan_time 没有逻辑删除字段，物理删除即可（计划没了时间点也没意义）
        planTimeMapper.delete(new LambdaQueryWrapper<PlanTime>().eq(PlanTime::getPlanId, id));
        return true;
    }

    /** 按 id + user_id 双条件查，越权过滤核心 */
    private MedicationPlan findOwned(Long userId, Long id) {
        return planMapper.selectOne(
                new LambdaQueryWrapper<MedicationPlan>()
                        .eq(MedicationPlan::getId, id)
                        .eq(MedicationPlan::getUserId, userId));
    }

    private void cancelPendingTasks(Long userId, Long planId) {
        taskMapper.update(null, new LambdaUpdateWrapper<RemindTask>()
                .eq(RemindTask::getUserId, userId).eq(RemindTask::getPlanId, planId)
                .in(RemindTask::getStatus, "WAITING", "TRIGGERED")
                .set(RemindTask::getStatus, "CANCELLED"));
    }

    private void retireFutureTasks(Long userId, MedicationPlan plan, LocalDateTime effectiveAt) {
        if ("ACTIVE".equals(plan.getStatus())) {
            taskMapper.triggerDueTasks(userId, plan.getId(), plan.getPlanVersion(), effectiveAt);
        }
        taskMapper.update(null, new LambdaUpdateWrapper<RemindTask>()
                .eq(RemindTask::getUserId, userId).eq(RemindTask::getPlanId, plan.getId())
                .eq(RemindTask::getStatus, "WAITING").gt(RemindTask::getScheduledAt, effectiveAt)
                .set(RemindTask::getStatus, "CANCELLED"));
    }

    private MedicationPlan findOwnedForUpdate(Long userId, Long id) {
        return planMapper.selectOne(new LambdaQueryWrapper<MedicationPlan>()
                .eq(MedicationPlan::getId, id).eq(MedicationPlan::getUserId, userId)
                .last("FOR UPDATE"));
    }

    private void validateRequest(PlanRequest req, boolean creating) {
        if (req.getStartDate() == null || (req.getEndDate() != null
                && req.getEndDate().isBefore(req.getStartDate()))) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (req.getDosagePerTime() != null && req.getDosagePerTime().signum() <= 0) {
            throw new IllegalArgumentException("单次服用量必须大于零");
        }
        if (req.getMealRelation() != null && (req.getMealRelation() < 0 || req.getMealRelation() > 4)) {
            throw new IllegalArgumentException("餐时类型不正确");
        }
        if (req.getRemindTimes() == null && !creating) {
            return;
        }
        if (req.getRemindTimes() == null || req.getRemindTimes().isEmpty()
                || req.getRemindTimes().size() > 24) {
            throw new IllegalArgumentException("每日提醒时间点必须为1至24个");
        }
        HashSet<LocalTime> times = new HashSet<>();
        for (String value : req.getRemindTimes()) {
            if (value == null || !value.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                throw new IllegalArgumentException("提醒时间格式必须为HH:mm");
            }
            if (!times.add(LocalTime.parse(value))) {
                throw new IllegalArgumentException("提醒时间点不能重复");
            }
        }
    }

    /** 入参拷贝到实体 */
    private void applyRequest(MedicationPlan plan, PlanRequest req) {
        plan.setMedicineId(req.getMedicineId());
        plan.setDosagePerTime(req.getDosagePerTime());
        plan.setDosageUnit(req.getDosageUnit());
        plan.setStartDate(req.getStartDate());
        plan.setEndDate(req.getEndDate());
        plan.setMealRelation(req.getMealRelation() == null ? 0 : req.getMealRelation());
        plan.setRemark(req.getRemark());
    }

    /** 保存时间点列表（sequence_no 按提交顺序编号） */
    private void saveTimes(Long planId, List<String> remindTimes) {
        if (remindTimes == null || remindTimes.isEmpty()) {
            return;
        }
        int seq = 1;
        for (String t : remindTimes) {
            PlanTime pt = new PlanTime();
            pt.setPlanId(planId);
            pt.setRemindTime(LocalTime.parse(t));
            pt.setSequenceNo(seq++);
            pt.setEnabled(1);
            planTimeMapper.insert(pt);
        }
    }

    /** 组装 VO：查时间点 + 查药品名 */
    private PlanVO toVO(MedicationPlan plan) {
        PlanVO vo = new PlanVO();
        vo.setId(plan.getId());
        vo.setPlanVersion(plan.getPlanVersion());
        vo.setMedicineId(plan.getMedicineId());
        vo.setDosagePerTime(plan.getDosagePerTime());
        vo.setDosageUnit(plan.getDosageUnit());
        vo.setStartDate(plan.getStartDate());
        vo.setEndDate(plan.getEndDate());
        vo.setMealRelation(plan.getMealRelation());
        vo.setStatus(plan.getStatus());
        vo.setRemark(plan.getRemark());
        vo.setCreateTime(plan.getCreateTime());
        vo.setUpdateTime(plan.getUpdateTime());

        // 时间点
        List<PlanTime> times = planTimeMapper.selectList(
                new LambdaQueryWrapper<PlanTime>()
                        .eq(PlanTime::getPlanId, plan.getId())
                        .orderByAsc(PlanTime::getSequenceNo));
        vo.setTimes(times);

        // 药品名
        Medicine medicine = medicineMapper.selectById(plan.getMedicineId());
        vo.setMedicineName(medicine == null ? null : medicine.getName());

        return vo;
    }
}
