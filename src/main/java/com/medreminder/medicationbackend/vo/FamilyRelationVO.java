package com.medreminder.medicationbackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家属关系展示对象。比实体多补了双方昵称，方便前端直接展示「谁照看谁」。
 */
@Data
public class FamilyRelationVO {

    private Long id;

    /** 被照护方 */
    private Long ownerUserId;
    private String ownerNickname;

    /** 家属 */
    private Long familyUserId;
    private String familyNickname;

    /** 关系称谓（父/母/子女/配偶） */
    private String relationType;

    /** PENDING/ACTIVE/REJECTED/REVOKED */
    private String relationStatus;

    /** 授权范围（逗号分隔） */
    private String scope;

    /** 邀请码（仅 PENDING 期间返回，便于 owner 分享给家属） */
    private String inviteCode;

    private LocalDateTime inviteExpireAt;
    private LocalDateTime bindTime;
    private LocalDateTime unbindTime;
    private LocalDateTime createTime;
}
