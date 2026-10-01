package com.medreminder.medicationbackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 family_relation 表（家属绑定与授权范围）。
 *
 * 方向约定（重要，读错会全盘错）：
 *   owner_user_id  = 被照护方（数据主体，用药的人，拥有授权权）
 *   family_user_id = 家属（查看的人，只能看 owner 授权范围内的数据）
 *
 * 状态机：PENDING（待确认）→ ACTIVE（生效）
 *                     ↘ REJECTED（家属拒绝）
 *         ACTIVE → REVOKED（owner 单方解除，保留记录不物理删除）
 *
 * 授权权在 owner 手里：绑定必须经 owner 发起邀请，解绑 owner 可单方发起。
 * 这正面回应导师方案第十五节「用户主动授权的家庭关系模型」。
 */
@Data
@TableName("family_relation")
public class FamilyRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被照护方（数据主体） */
    private Long ownerUserId;

    /** 家属用户 */
    private Long familyUserId;

    /** 关系称谓（父/母/子女/配偶，用户自选） */
    private String relationType;

    /** PENDING/ACTIVE/REJECTED/REVOKED */
    private String relationStatus;

    /** 授权范围，逗号分隔：PLAN_STATUS/ABNORMAL_EVENT/RECORD_DETAIL/STATISTICS */
    private String scope;

    /** 邀请码（仅 PENDING 期间有效） */
    private String inviteCode;

    /** 邀请过期时间 */
    private LocalDateTime inviteExpireAt;

    /** 关系生效时间 */
    private LocalDateTime bindTime;

    /** 解除时间（保留记录） */
    private LocalDateTime unbindTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
