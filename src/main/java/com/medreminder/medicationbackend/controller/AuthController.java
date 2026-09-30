package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.dto.LoginRequest;
import com.medreminder.medicationbackend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。导师方案接口清单第 1 个：POST /api/auth/login
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。
     * 请求：POST /api/auth/login   { "account": "...", "password": "..." }
     * @Valid 触发 LoginRequest 上的 @NotBlank 校验，不通过自动返回 400
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getAccount(), request.getPassword());
    }
}
