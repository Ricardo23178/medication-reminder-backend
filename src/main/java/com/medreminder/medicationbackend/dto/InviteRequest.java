package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起家属绑定邀请的请求体。
 * 由「被照护方（owner）」调用：生成一个邀请码，家属凭码接受绑定。
 *
 *   { "relationType": "父", "scope": "PLAN_STATUS,ABNORMAL_EVENT" }
 *
 * relationType：关系称谓，用户自选（父/母/子女/配偶等），可选。
 * scope        ：授权范围（逗号分隔），可选；不传用默认 PLAN_STATUS,ABNORMAL_EVENT。
 */
@Data
public class InviteRequest {

    /** 关系称谓（可选，例如「父」「母」「子女」） */
    private String relationType;

    /** 授权范围（可选，逗号分隔），默认 PLAN_STATUS,ABNORMAL_EVENT */
    private String scope;
}
