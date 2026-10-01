package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.dto.MedicineRequest;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.service.MedicineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 药品档案接口 —— 导师方案接口清单第 2 个：medicines。
 * 完整的增删改查（CRUD）：
 *   GET    /api/medicines          查列表（仅本人）
 *   POST   /api/medicines          新增
 *   GET    /api/medicines/{id}     查单条（仅本人）
 *   PUT    /api/medicines/{id}     修改（仅本人）
 *   DELETE /api/medicines/{id}     删除（仅本人，逻辑删除）
 * 全部需要登录（拦截器已拦），业务里再做 user_id 级越权过滤。
 */
@RestController
@RequestMapping("/api/medicines")
public class MedicineController {

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    /** 查列表 */
    @GetMapping
    public Result<List<Medicine>> list() {
        return Result.ok(medicineService.listByUser(UserContext.userId()));
    }

    /** 新增 */
    @PostMapping
    public Result<Medicine> create(@Valid @RequestBody MedicineRequest req) {
        return Result.ok(medicineService.create(UserContext.userId(), req));
    }

    /** 查单条 */
    @GetMapping("/{id}")
    public Result<Medicine> detail(@PathVariable Long id) {
        Medicine m = medicineService.getOwned(UserContext.userId(), id);
        if (m == null) {
            return Result.error(403, "药品不存在或无权查看");
        }
        return Result.ok(m);
    }

    /** 修改 */
    @PutMapping("/{id}")
    public Result<Medicine> update(@PathVariable Long id, @Valid @RequestBody MedicineRequest req) {
        Medicine m = medicineService.update(UserContext.userId(), id, req);
        if (m == null) {
            return Result.error(403, "药品不存在或无权修改");
        }
        return Result.ok(m);
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        boolean ok = medicineService.delete(UserContext.userId(), id);
        if (!ok) {
            return Result.error(403, "药品不存在或无权删除");
        }
        return Result.ok(null);
    }
}
