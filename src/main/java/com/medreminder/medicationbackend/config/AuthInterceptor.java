package com.medreminder.medicationbackend.config;

import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器。
 * 规则：请求头必须带 Authorization: Bearer <token>，token 有效才放行，
 *      并把用户身份写入 UserContext，供后续业务读取。
 * 放行哪些路径由 WebConfig 配置（登录、健康检查免检）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "未登录或缺少凭证");
            return false;
        }

        String token = auth.substring(7).trim();
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get("role", String.class);
            UserContext.set(new UserContext.CurrentUser(userId, role));
            return true; // 校验通过，放行
        } catch (Exception e) {
            writeUnauthorized(response, "登录已过期或凭证无效");
            return false;
        }
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
