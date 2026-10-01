package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 确认服药的入参。
 * action：TAKEN已服 / DELAYED稍后 / SKIPPED跳过。
 * 说明：不把「漏服/超时」作为用户可提交的动作 —— 那由系统定时判定（UNCONFIRMED），
 * 用户只能主动表达「已服/稍后/跳过」三种意愿。
 */
@Data
public class ConfirmRequest {

    /** TAKEN / DELAYED / SKIPPED */
    @NotBlank(message = "操作类型不能为空")
    private String action;

    /** 「稍后」顺延的分钟数（action=DELAYED 时用） */
    private Integer delayMinutes;

    /** 「跳过」原因（action=SKIPPED 时用，用户自报） */
    private String skipReason;

    /** 客户端幂等键（可选，弱网重传去重用） */
    private String clientRequestId;
}
