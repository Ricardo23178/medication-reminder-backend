package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户接口。GET /api/user/me 用来验证「凭 token 认人」是否生效：
 * 不带 token 访问 → 401；带登录拿到的 token → 200 返回当前用户身份。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        UserContext.CurrentUser u = UserContext.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.id());
        data.put("role", u.role());
        return Result.ok(data);
    }
}
