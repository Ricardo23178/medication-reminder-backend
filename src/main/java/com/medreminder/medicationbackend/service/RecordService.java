package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.MedicationRecordMapper;
import com.medreminder.medicationbackend.mapper.MedicineMapper;
import com.medreminder.medicationbackend.mapper.PlanMapper;
import com.medreminder.medicationbackend.mapper.RemindTaskMapper;
import com.medreminder.medicationbackend.vo.MedicationRecordVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 服药记录查询 —— 任务书第 3 条「记录查询」。
 * 只查当前登录用户自己的记录（user_id 取自 token），越权兜底。
 */
@Service
public class RecordService {

    private final MedicationRecordMapper recordMapper;
    private final RemindTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final MedicineMapper medicineMapper;

    public RecordService(MedicationRecordMapper recordMapper,
                         RemindTaskMapper taskMapper,
                         PlanMapper planMapper,
                         MedicineMapper medicineMapper) {
        this.recordMapper = recordMapper;
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.medicineMapper = medicineMapper;
    }

    /**
     * 查当前用户的服药记录（按操作时间倒序）。
     */
    public List<MedicationRecordVO> listMine(Long userId) {
        List<MedicationRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<MedicationRecord>()
                        .eq(MedicationRecord::getUserId, userId)
                        .orderByDesc(MedicationRecord::getActionTime));

        List<MedicationRecordVO> result = new ArrayList<>();
        for (MedicationRecord r : records) {
            result.add(toVO(r));
        }
        return result;
    }

    /**
     * 组装 VO：补药品名（记录 → 任务 → 计划 → 药品）。
     */
    private MedicationRecordVO toVO(MedicationRecord r) {
        MedicationRecordVO vo = new MedicationRecordVO();
        vo.setId(r.getId());
        vo.setTaskId(r.getTaskId());
        vo.setAction(r.getAction());
        vo.setActionTime(r.getActionTime());
        vo.setDelayMinutes(r.getDelayMinutes());
        vo.setSkipReason(r.getSkipReason());

        if (r.getTaskId() != null) {
            RemindTask task = taskMapper.selectById(r.getTaskId());
            if (task != null && task.getPlanId() != null) {
                MedicationPlan plan = planMapper.selectById(task.getPlanId());
                if (plan != null && plan.getMedicineId() != null) {
                    Medicine medicine = medicineMapper.selectById(plan.getMedicineId());
                    if (medicine != null) {
                        vo.setMedicineName(medicine.getName());
                    }
                }
            }
        }
        return vo;
    }
}
