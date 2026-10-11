package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.service.RecordService;
import com.medreminder.medicationbackend.vo.MedicationRecordVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服药记录接口 —— 任务书第 3 条「记录查询」。
 *   GET /api/records   查询当前用户的服药记录（按时间倒序）。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public Result<List<MedicationRecordVO>> list() {
        return Result.ok(recordService.listMine(UserContext.userId()));
    }
}
