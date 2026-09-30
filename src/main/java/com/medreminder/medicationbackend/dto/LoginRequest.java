package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求体。前端 POST 过来的 JSON：
 *   { "account": "admin", "password": "xxx" }
 */
@Data
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;
}
