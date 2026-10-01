package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 家属接受绑定邀请的请求体。
 * 由「家属（family）」调用：输入 owner 给的邀请码，接受绑定。
 *
 *   { "inviteCode": "A1B2C3D4" }
 */
@Data
public class AcceptInviteRequest {

    @NotBlank(message = "邀请码不能为空")
    private String inviteCode;
}
