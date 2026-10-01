package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.dto.PlanRequest;
import com.medreminder.medicationbackend.service.PlanService;
import com.medreminder.medicationbackend.vo.PlanVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用药计划接口 —— 导师方案接口清单第 3、4 个：plans、plans/{id}/pause。
 *   GET    /api/plans          查计划列表
 *   POST   /api/plans          新增计划（含时间点）
 *   GET    /api/plans/{id}     查计划详情
 *   PUT    /api/plans/{id}     修改计划
 *   DELETE /api/plans/{id}     删除计划
 *   POST   /api/plans/{id}/pause    暂停计划（ACTIVE→PAUSED）
 *   POST   /api/plans/{id}/resume   恢复计划（PAUSED→ACTIVE）
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 查列表 */
    @GetMapping
    public Result<List<PlanVO>> list() {
        return Result.ok(planService.listByUser(UserContext.userId()));
    }

    /** 新增 */
    @PostMapping
    public Result<PlanVO> create(@Valid @RequestBody PlanRequest req) {
        try {
            return Result.ok(planService.create(UserContext.userId(), req));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /** 查详情 */
    @GetMapping("/{id}")
    public Result<PlanVO> detail(@PathVariable Long id) {
        PlanVO vo = planService.getOwned(UserContext.userId(), id);
        if (vo == null) {
            return Result.error(403, "计划不存在或无权查看");
        }
        return Result.ok(vo);
    }

    /** 修改 */
    @PutMapping("/{id}")
    public Result<PlanVO> update(@PathVariable Long id, @Valid @RequestBody PlanRequest req) {
        PlanVO vo = planService.update(UserContext.userId(), id, req);
        if (vo == null) {
            return Result.error(403, "计划不存在或无权修改");
        }
        return Result.ok(vo);
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        boolean ok = planService.delete(UserContext.userId(), id);
        if (!ok) {
            return Result.error(403, "计划不存在或无权删除");
        }
        return Result.ok(null);
    }

    /** 暂停 */
    @PostMapping("/{id}/pause")
    public Result<PlanVO> pause(@PathVariable Long id) {
        PlanVO vo = planService.pause(UserContext.userId(), id);
        if (vo == null) {
            return Result.error(400, "计划不存在或当前状态不可暂停");
        }
        return Result.ok(vo);
    }

    /** 恢复 */
    @PostMapping("/{id}/resume")
    public Result<PlanVO> resume(@PathVariable Long id) {
        PlanVO vo = planService.resume(UserContext.userId(), id);
        if (vo == null) {
            return Result.error(400, "计划不存在或当前状态不可恢复");
        }
        return Result.ok(vo);
    }
}
