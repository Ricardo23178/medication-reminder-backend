package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.dto.LoginRequest;
import com.medreminder.medicationbackend.dto.RegisterRequest;
import com.medreminder.medicationbackend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。
 *   POST /api/auth/register 注册（返回 token，注册即登录）
 *   POST /api/auth/login    登录
 * 两个接口都在 WebConfig 里放行，无需 token 即可访问。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册。
     * 请求：POST /api/auth/register
     *   { "account": "zhangsan", "password": "123456", "confirmPassword": "123456",
     *     "nickname": "张三", "phone": "13800138000" }
     * 成功返回与登录一致：{ code, message, data: { token, tokenType, expiresIn, user } }
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
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
