package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.dto.RegisterRequest;
import com.medreminder.medicationbackend.entity.User;
import com.medreminder.medicationbackend.mapper.UserMapper;
import com.medreminder.medicationbackend.util.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证业务：注册（建账号）与登录（验密码 → 签发 JWT）。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    /** BCrypt 编码器：库里永远只存哈希，绝不存明文 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    // ==================== 登录 ====================

    /**
     * 登录。成功返回 token + 用户基本信息；失败返回统一错误提示。
     * 账号不存在和密码错误返回同一句话 —— 防止攻击者探测哪些账号已注册。
     */
    public Result<Map<String, Object>> login(String account, String password) {
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

        return Result.ok(buildAuthData(user));
    }

    // ==================== 注册 ====================

    /**
     * 注册新用户。返回结构与登录一致（token + user），即「注册即登录」。
     *
     * 关键设计：
     * 1. 只开放普通用户（USER）自注册 —— 管理员（ADMIN）不提供注册入口，防提权。
     * 2. 家属身份不写进 role（方案约定 role 只管「是否平台管理员」），
     *    家属关系由 family_relation 表动态决定，因此注册出来统一是 USER。
     * 3. 账号唯一性：先预检给友好提示；并发兜底靠 sys_user 的 uk_account 唯一键。
     * 4. 密码 BCrypt 加密后落库，明文不落地、不回传。
     */
    public Result<Map<String, Object>> register(RegisterRequest req) {
        // 1) 两次密码一致性（跨字段，放这里比对最直观）
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            return Result.error(Result.BAD_REQUEST, "两次输入的密码不一致");
        }

        // 2) 账号唯一性预检：命中即拒，给出明确提示
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getAccount, req.getAccount()));
        if (exists != null && exists > 0) {
            return Result.error(Result.BAD_REQUEST, "该账号已被注册，请换一个");
        }

        // 3) 组装实体
        User user = new User();
        user.setAccount(req.getAccount());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword())); // 加密，绝不明文
        user.setNickname(req.getNickname());
        user.setPhone(normalizePhone(req.getPhone()));
        user.setRole("USER");   // 硬编码 USER：断掉「注册即管理员」的提权路径
        user.setStatus(1);

        // 4) 落库：并发下两个请求同时通过预检时，靠 uk_account 唯一键兜底
        try {
            userMapper.insert(user);   // 插入后 MyBatis-Plus 回填自增 id
        } catch (DuplicateKeyException e) {
            return Result.error(Result.BAD_REQUEST, "该账号已被注册，请换一个");
        }

        // 5) 注册即登录：直接签发 token，省掉前端再调一次登录
        return Result.ok(buildAuthData(user));
    }

    // ==================== 内部方法 ====================

    /** 手机号归一化：空串转 null，避免落库空字符串脏数据 */
    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return phone.trim();
    }

    /** 组装「token + 用户信息」返回体（登录、注册共用，保证两处结构一致） */
    private Map<String, Object> buildAuthData(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtUtil.generate(user.getId(), user.getRole()));
        data.put("tokenType", "Bearer");
        data.put("expiresIn", jwtUtil.getExpireSeconds());

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("account", user.getAccount());
        userInfo.put("nickname", user.getNickname());
        userInfo.put("role", user.getRole());
        data.put("user", userInfo);
        return data;
    }
}
