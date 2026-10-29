package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.MedicationRecord;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.mapper.MedicationRecordMapper;
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

    public RecordService(MedicationRecordMapper recordMapper,
                         RemindTaskMapper taskMapper) {
        this.recordMapper = recordMapper;
        this.taskMapper = taskMapper;
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
     * 组装 VO：读取任务生成时的药品名，不借用当前计划覆盖历史。
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
            if (task != null) vo.setMedicineName(task.historicalMedicineName());
        }
        return vo;
    }
}
