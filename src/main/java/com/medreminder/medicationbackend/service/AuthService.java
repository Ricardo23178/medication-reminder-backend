package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.entity.User;
import com.medreminder.medicationbackend.mapper.UserMapper;
import com.medreminder.medicationbackend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录业务：查账号 → 验密码（BCrypt）→ 签发 JWT。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    /** BCrypt 编码器：明文密码和库里哈希比对，库里永不存明文 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 登录。成功返回 token + 用户基本信息；失败返回统一错误提示。
     * 账号不存在和密码错误返回同一句话 —— 防止攻击者探测哪些账号已注册。
     */
    public Result<Map<String, Object>> login(String account, String password) {
        // 按账号查用户（LambdaQueryWrapper：用实体字段名写条件，防 SQL 注入）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getAccount, account));

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return Result.error(Result.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.error(Result.UNAUTHORIZED, "账号已被禁用，请联系管理员");
        }

        // 更新最近登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        // 签发 JWT（生成规则统一收敛在 JwtUtil）
        String token = jwtUtil.generate(user.getId(), user.getRole());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("expiresIn", jwtUtil.getExpireSeconds());
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("account", user.getAccount());
        userInfo.put("nickname", user.getNickname());
        userInfo.put("role", user.getRole());
        data.put("user", userInfo);

        return Result.ok(data);
    }
}
