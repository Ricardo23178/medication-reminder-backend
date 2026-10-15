package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medreminder.medicationbackend.dto.AcceptInviteRequest;
import com.medreminder.medicationbackend.dto.InviteRequest;
import com.medreminder.medicationbackend.entity.AbnormalEvent;
import com.medreminder.medicationbackend.entity.FamilyRelation;
import com.medreminder.medicationbackend.entity.MedicationPlan;
import com.medreminder.medicationbackend.entity.Medicine;
import com.medreminder.medicationbackend.entity.RemindTask;
import com.medreminder.medicationbackend.entity.User;
import com.medreminder.medicationbackend.mapper.AbnormalEventMapper;
import com.medreminder.medicationbackend.mapper.FamilyRelationMapper;
import com.medreminder.medicationbackend.mapper.MedicineMapper;
import com.medreminder.medicationbackend.mapper.NotificationMapper;
import com.medreminder.medicationbackend.mapper.PlanMapper;
import com.medreminder.medicationbackend.mapper.RemindTaskMapper;
import com.medreminder.medicationbackend.mapper.UserMapper;
import com.medreminder.medicationbackend.vo.AbnormalEventVO;
import com.medreminder.medicationbackend.vo.FamilyRelationVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * 家属协同业务 —— 项目「协同」二字的落地。
 *
 * 方向约定（读错会全盘错）：
 *   owner_user_id  = 被照护方（数据主体，用药的人，拥有授权权）
 *   family_user_id = 家属（查看的人，只能在授权范围内看 owner 的数据）
 *
 * 核心安全点（导师方案第十一节第 4、5 条）：
 * 1. 授权在 owner 手里：owner 发起邀请（生成邀请码），家属凭码接受，绑定才算成立。
 * 2. 数据级权限：「是家属」不等于能看全部 —— 查询一律按关系状态 + 授权范围过滤，
 *    解绑（REVOKED）后立即失去访问权限。
 * 3. 防自己绑自己、防邀请码过期/重放。
 */
@Service
public class FamilyService {

    /** 邀请码有效期（小时） */
    private static final int INVITE_EXPIRE_HOURS = 24;
    /** 默认授权范围（导师 schema 默认值一致） */
    private static final String DEFAULT_SCOPE = "PLAN_STATUS,ABNORMAL_EVENT";
    /** 邀请码字符集：去掉 0/O、1/I 等易混淆字符 */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FamilyRelationMapper relationMapper;
    private final UserMapper userMapper;
    private final AbnormalEventMapper eventMapper;
    private final RemindTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final MedicineMapper medicineMapper;
    private final NotificationMapper notificationMapper;

    public FamilyService(FamilyRelationMapper relationMapper,
                         UserMapper userMapper,
                         AbnormalEventMapper eventMapper,
                         RemindTaskMapper taskMapper,
                         PlanMapper planMapper,
                         MedicineMapper medicineMapper,
                         NotificationMapper notificationMapper) {
        this.relationMapper = relationMapper;
        this.userMapper = userMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.medicineMapper = medicineMapper;
        this.notificationMapper = notificationMapper;
    }

    // ==================== 邀请与接受 ====================

    /**
     * 发起绑定邀请（owner 调用）。
     * 生成邀请码 + 落一条 PENDING 关系；返回邀请码给 owner 分享给家属。
     * 同一对 owner-family 若已存在关系，则复用旧记录刷新邀请码（避免唯一键冲突 + 重复记录）。
     */
    @Transactional
    public FamilyRelationVO invite(Long ownerUserId, InviteRequest req) {
        String code = generateInviteCode();
        String scope = (req.getScope() == null || req.getScope().isBlank())
                ? DEFAULT_SCOPE : req.getScope();
        List<String> scopes = Arrays.stream(scope.split(",", -1)).map(String::trim).distinct().toList();
        if (scopes.stream().anyMatch(s -> !"PLAN_STATUS".equals(s) && !"ABNORMAL_EVENT".equals(s))) {
            throw new IllegalArgumentException("授权范围只能为PLAN_STATUS或ABNORMAL_EVENT");
        }
        scope = String.join(",", scopes);

        // 复用已存在的 PENDING 邀请（同一 owner 多次发起，只保留一条待确认记录）
        FamilyRelation rel = relationMapper.selectOne(
                new LambdaQueryWrapper<FamilyRelation>()
                        .eq(FamilyRelation::getOwnerUserId, ownerUserId)
                        .eq(FamilyRelation::getRelationStatus, "PENDING")
                        .orderByDesc(FamilyRelation::getId)
                        .last("LIMIT 1"));

        if (rel == null) {
            rel = new FamilyRelation();
            rel.setOwnerUserId(ownerUserId);
            rel.setRelationStatus("PENDING");
        }

        rel.setRelationType(req.getRelationType());
        rel.setScope(scope);
        rel.setInviteCode(code);
        rel.setInviteExpireAt(LocalDateTime.now().plusHours(INVITE_EXPIRE_HOURS));

        if (rel.getId() == null) {
            relationMapper.insert(rel);
        } else {
            relationMapper.updateById(rel);
        }

        return toVO(rel);
    }

    /**
     * 家属凭邀请码接受绑定（family 调用）。
     * 返回码语义（与 ReminderService.confirm 同风格）：
     *   0 = 绑定成功
     *   1 = 邀请码不存在
     *   2 = 邀请码已过期
     *   3 = 不能绑定自己
     *   4 = 邀请已被处理（已生效/已拒绝/已解除）
     */
    @Transactional
    public int accept(Long familyUserId, AcceptInviteRequest req) {
        FamilyRelation rel = relationMapper.selectOne(
                new LambdaQueryWrapper<FamilyRelation>()
                        .eq(FamilyRelation::getInviteCode, req.getInviteCode()));

        if (rel == null) {
            return 1;
        }
        if (!"PENDING".equals(rel.getRelationStatus())) {
            return 4;
        }
        if (rel.getInviteExpireAt() != null && LocalDateTime.now().isAfter(rel.getInviteExpireAt())) {
            return 2;
        }
        if (rel.getOwnerUserId().equals(familyUserId)) {
            return 3;
        }

        // 绑定生效：PENDING → ACTIVE
        try {
            // 状态条件更新保证同一个邀请码只被一个家属领取；显式清空邀请码。
            int updated = relationMapper.update(null, new LambdaUpdateWrapper<FamilyRelation>()
                    .eq(FamilyRelation::getId, rel.getId())
                    .eq(FamilyRelation::getRelationStatus, "PENDING")
                    .eq(FamilyRelation::getInviteCode, req.getInviteCode())
                    .gt(FamilyRelation::getInviteExpireAt, LocalDateTime.now())
                    .set(FamilyRelation::getFamilyUserId, familyUserId)
                    .set(FamilyRelation::getRelationStatus, "ACTIVE")
                    .set(FamilyRelation::getBindTime, LocalDateTime.now())
                    .set(FamilyRelation::getInviteCode, null)
                    .set(FamilyRelation::getInviteExpireAt, null));
            if (updated == 0) {
                return 4;
            }
        } catch (DuplicateKeyException e) {
            // uk_owner_family 兜底：同一对关系已存在（并发接受），视为已处理
            return 4;
        }

        return 0;
    }

    // ==================== 查询 ====================

    /**
     * 查当前用户相关的所有关系（owner 视角 + family 视角合并）。
     * 越权：只返回 ownerUserId == 我 或 familyUserId == 我 的记录。
     */
    public List<FamilyRelationVO> listMine(Long userId) {
        List<FamilyRelation> list = relationMapper.selectList(
                new LambdaQueryWrapper<FamilyRelation>()
                        .and(w -> w.eq(FamilyRelation::getOwnerUserId, userId)
                                .or().eq(FamilyRelation::getFamilyUserId, userId))
                        .orderByDesc(FamilyRelation::getCreateTime));

        List<FamilyRelationVO> result = new ArrayList<>();
        for (FamilyRelation rel : list) {
            result.add(toVO(rel));
        }
        return result;
    }

    /** 查单个关系（越权过滤：非 owner 且非 family 返回 null） */
    public FamilyRelationVO getOwned(Long userId, Long id) {
        FamilyRelation rel = relationMapper.selectById(id);
        if (rel == null) {
            return null;
        }
        boolean isOwner = rel.getOwnerUserId().equals(userId);
        boolean isFamily = rel.getFamilyUserId() != null && rel.getFamilyUserId().equals(userId);
        if (!isOwner && !isFamily) {
            return null;
        }
        return toVO(rel);
    }

    // ==================== 解绑 ====================

    /**
     * 解除关系（owner 单方发起）。ACTIVE/PENDING → REVOKED。
     * 解绑后立即停止后续协同数据访问（异常查询按 ACTIVE 过滤，天然失效）。
     */
    @Transactional
    public boolean unbind(Long ownerUserId, Long relationId) {
        FamilyRelation rel = relationMapper.selectOne(new LambdaQueryWrapper<FamilyRelation>()
                .eq(FamilyRelation::getId, relationId)
                .eq(FamilyRelation::getOwnerUserId, ownerUserId).last("FOR UPDATE"));
        if (rel == null || !rel.getOwnerUserId().equals(ownerUserId)) {
            return false;
        }
        if ("REVOKED".equals(rel.getRelationStatus())) {
            return false;
        }

        if (rel.getFamilyUserId() != null) {
            // 用状态永久撤销旧消息，避免同一秒内重绑突破DATETIME精度的时间比较。
            notificationMapper.revokeFamilyMessages(ownerUserId, rel.getFamilyUserId());
        }

        // 释放唯一键：把 family_user_id 置空，允许日后同一对重新绑定。
        // 注意不能用 updateById（MyBatis-Plus 默认跳过 null 字段），必须用 wrapper 显式 set null。
        relationMapper.update(null, new LambdaUpdateWrapper<FamilyRelation>()
                .eq(FamilyRelation::getId, relationId)
                .set(FamilyRelation::getRelationStatus, "REVOKED")
                .set(FamilyRelation::getFamilyUserId, null)
                .set(FamilyRelation::getUnbindTime, LocalDateTime.now())
                .set(FamilyRelation::getInviteCode, null)
                .set(FamilyRelation::getInviteExpireAt, null));
        return true;
    }

    // ==================== 异常事件（家属视角） ====================

    /**
     * 家属查看「被授权对象」的异常事件。
     * 越权核心：先查当前用户（作为 family）名下所有 ACTIVE 关系，拿到 owner 列表，
     * 再查这些 owner 的异常事件 —— 解绑后关系不再是 ACTIVE，自然查不到，权限即时失效。
     */
    public List<AbnormalEventVO> listAuthorizedAbnormalEvents(Long familyUserId) {
        return listAuthorizedAbnormalEvents(familyUserId, false);
    }

    public List<AbnormalEventVO> listAuthorizedAbnormalEvents(Long familyUserId, boolean includeClosed) {
        // 1) 查我是家属、且关系生效中的 owner 列表
        List<FamilyRelation> actives = relationMapper.selectList(
                new LambdaQueryWrapper<FamilyRelation>()
                        .eq(FamilyRelation::getFamilyUserId, familyUserId)
                        .eq(FamilyRelation::getRelationStatus, "ACTIVE"));

        if (actives.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> ownerIds = actives.stream()
                .filter(rel -> hasAbnormalScope(rel.getScope()))
                .map(FamilyRelation::getOwnerUserId)
                .distinct()
                .toList();
        if (ownerIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 2) 查这些 owner 的异常事件（未关闭的优先）
        List<AbnormalEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<AbnormalEvent>()
                        .in(AbnormalEvent::getUserId, ownerIds)
                        .ne(!includeClosed, AbnormalEvent::getStatus, "CLOSED")
                        .orderByDesc(AbnormalEvent::getOccurredAt));

        List<AbnormalEventVO> result = new ArrayList<>();
        for (AbnormalEvent e : events) {
            result.add(toEventVO(e));
        }
        return result;
    }

    public static boolean hasAbnormalScope(String scope) {
        return scope != null && Arrays.stream(scope.split(",")).map(String::trim)
                .anyMatch("ABNORMAL_EVENT"::equals);
    }

    /** 返回0成功、1无授权、2状态不允许。先锁关系，再锁事件，与解绑操作串行化。 */
    @Transactional
    public int handleAbnormalEvent(Long familyUserId, Long eventId, boolean close) {
        AbnormalEvent candidate = eventMapper.selectById(eventId);
        if (candidate == null) return 1;
        FamilyRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<FamilyRelation>()
                .eq(FamilyRelation::getOwnerUserId, candidate.getUserId())
                .eq(FamilyRelation::getFamilyUserId, familyUserId)
                .eq(FamilyRelation::getRelationStatus, "ACTIVE").last("FOR UPDATE"));
        if (relation == null || !hasAbnormalScope(relation.getScope())) return 1;
        AbnormalEvent event = eventMapper.selectOne(new LambdaQueryWrapper<AbnormalEvent>()
                .eq(AbnormalEvent::getId, eventId).last("FOR UPDATE"));
        if (event == null) return 1;
        if (close) {
            if ("CLOSED".equals(event.getStatus())) return 0;
            if (!"ACKNOWLEDGED".equals(event.getStatus())) return 2;
            event.setStatus("CLOSED");
            event.setClosedAt(LocalDateTime.now());
        } else {
            if ("ACKNOWLEDGED".equals(event.getStatus())) return 0;
            if (!"OPEN".equals(event.getStatus()) && !"NOTIFIED".equals(event.getStatus())) return 2;
            event.setStatus("ACKNOWLEDGED");
            event.setAcknowledgedBy(familyUserId);
            event.setAcknowledgedAt(LocalDateTime.now());
        }
        eventMapper.updateById(event);
        return 0;
    }

    // ==================== 内部方法 ====================

    /** 生成随机邀请码（8 位，去易混淆字符） */
    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 组装关系 VO：补双方昵称 */
    private FamilyRelationVO toVO(FamilyRelation rel) {
        FamilyRelationVO vo = new FamilyRelationVO();
        vo.setId(rel.getId());
        vo.setOwnerUserId(rel.getOwnerUserId());
        vo.setFamilyUserId(rel.getFamilyUserId());
        vo.setRelationType(rel.getRelationType());
        vo.setRelationStatus(rel.getRelationStatus());
        vo.setScope(rel.getScope());
        vo.setInviteCode(rel.getInviteCode());
        vo.setInviteExpireAt(rel.getInviteExpireAt());
        vo.setBindTime(rel.getBindTime());
        vo.setUnbindTime(rel.getUnbindTime());
        vo.setCreateTime(rel.getCreateTime());

        User owner = userMapper.selectById(rel.getOwnerUserId());
        vo.setOwnerNickname(owner == null ? null : owner.getNickname());
        if (rel.getFamilyUserId() != null) {
            User family = userMapper.selectById(rel.getFamilyUserId());
            vo.setFamilyNickname(family == null ? null : family.getNickname());
        }
        return vo;
    }

    /** 组装异常事件 VO：补用户昵称、药品名、计划时间 */
    private AbnormalEventVO toEventVO(AbnormalEvent e) {
        AbnormalEventVO vo = new AbnormalEventVO();
        vo.setId(e.getId());
        vo.setUserId(e.getUserId());
        vo.setTaskId(e.getTaskId());
        vo.setType(e.getType());
        vo.setStatus(e.getStatus());
        vo.setOccurredAt(e.getOccurredAt());
        vo.setAcknowledgedAt(e.getAcknowledgedAt());
        vo.setClosedAt(e.getClosedAt());
        vo.setCreateTime(e.getCreateTime());

        User user = userMapper.selectById(e.getUserId());
        vo.setUserNickname(user == null ? null : user.getNickname());

        // 任务 → 计划 → 药品，补药品名和计划触发时间
        RemindTask task = taskMapper.selectById(e.getTaskId());
        if (task != null) {
            vo.setScheduledAt(task.getScheduledAt());
            MedicationPlan plan = planMapper.selectById(task.getPlanId());
            if (plan != null) {
                Medicine medicine = medicineMapper.selectById(plan.getMedicineId());
                vo.setMedicineName(medicine == null ? null : medicine.getName());
            }
        }
        return vo;
    }
}
