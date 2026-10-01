package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.dto.MedicineRequest;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.mapper.MedicineMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 药品档案业务。
 * 核心是「数据级越权过滤」：所有操作都限定在 user_id = 当前登录人 的范围内，
 * 改/删某条前必须先确认这条药属于当前用户，否则返回 403 —— 这是导师方案
 * 第三节「谁的数据谁拥有」的落地，也是答辩必讲的安全点。
 */
@Service
public class MedicineService {

    private final MedicineMapper medicineMapper;

    public MedicineService(MedicineMapper medicineMapper) {
        this.medicineMapper = medicineMapper;
    }

    /** 查当前用户的药品列表（按创建时间倒序） */
    public List<Medicine> listByUser(Long userId) {
        return medicineMapper.selectList(
                new LambdaQueryWrapper<Medicine>()
                        .eq(Medicine::getUserId, userId)
                        .orderByDesc(Medicine::getCreateTime));
    }

    /** 新增一条药品档案，归属当前用户 */
    public Medicine create(Long userId, MedicineRequest req) {
        Medicine m = new Medicine();
        m.setUserId(userId);
        applyRequest(m, req);
        m.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        medicineMapper.insert(m);
        return m;
    }

    /** 修改药品档案。先校验归属，不是自己的返回 null（由 Controller 转 403） */
    public Medicine update(Long userId, Long id, MedicineRequest req) {
        Medicine exist = findOwned(userId, id);
        if (exist == null) {
            return null;
        }
        applyRequest(exist, req);
        if (req.getEnabled() != null) {
            exist.setEnabled(req.getEnabled());
        }
        medicineMapper.updateById(exist);
        return exist;
    }

    /** 删除药品档案（逻辑删除）。先校验归属。返回 true=成功，false=无权 */
    public boolean delete(Long userId, Long id) {
        Medicine exist = findOwned(userId, id);
        if (exist == null) {
            return false;
        }
        medicineMapper.deleteById(id);
        return true;
    }

    /** 查一条，且必须是当前用户自己的；否则返回 null */
    public Medicine getOwned(Long userId, Long id) {
        return findOwned(userId, id);
    }

    /**
     * 按 id + user_id 双重条件查 —— 越权过滤的关键：
     * 别人传一个不属于自己的 medicine id，这里查不到，直接返回 null。
     */
    private Medicine findOwned(Long userId, Long id) {
        return medicineMapper.selectOne(
                new LambdaQueryWrapper<Medicine>()
                        .eq(Medicine::getId, id)
                        .eq(Medicine::getUserId, userId));
    }

    /** 把入参拷贝到实体（新增和修改共用） */
    private void applyRequest(Medicine m, MedicineRequest req) {
        m.setName(req.getName());
        m.setSpecification(req.getSpecification());
        m.setDosage(req.getDosage());
        m.setUnit(req.getUnit());
        m.setRemark(req.getRemark());
        m.setImageUrl(req.getImageUrl());
    }
}
