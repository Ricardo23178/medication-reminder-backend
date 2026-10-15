package com.medreminder.medicationbackend.config;

import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.util.JwtUtil;
import com.medreminder.medicationbackend.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import com.medreminder.medicationbackend.admin.AdminController;

/**
 * 登录校验拦截器。
 * 规则：请求头必须带 Authorization: Bearer <token>，token 有效才放行，
 *      并把用户身份写入 UserContext，供后续业务读取。
 * 放行哪些路径由 WebConfig 配置（登录、健康检查免检）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper users;

    public AuthInterceptor(JwtUtil jwtUtil, UserMapper users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        UserContext.clear();
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "未登录或缺少凭证");
            return false;
        }

        String token = auth.substring(7).trim();
        Long userId;
        try {
            Claims claims = jwtUtil.parse(token);
            userId = Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            writeUnauthorized(response, "登录已过期或凭证无效");
            return false;
        }
        // Current database identity is authoritative: disabling/deleting a user revokes old JWTs.
        var user = users.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            writeUnauthorized(response, "账号不可用，请重新登录或联系管理员");
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean adminHandler = handler instanceof HandlerMethod method
                && AdminController.class.isAssignableFrom(method.getBeanType());
        if ((adminHandler || path.equals("/api/admin") || path.startsWith("/api/admin/")) && !"ADMIN".equals(user.getRole())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"仅平台管理员可访问\"}");
            return false;
        }
        UserContext.set(new UserContext.CurrentUser(userId, user.getRole()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear(); // 请求结束清 ThreadLocal，防串号
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }
}
