package com.medreminder.medicationbackend.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口 —— 后端写的第一个接口。
 * 作用：验证 Web 服务能响应 + 数据库连接是通的。
 * 相当于鸿蒙里给 UIAbility 做的最小化启动验证。
 */
@RestController  // = 这个类里所有方法的返回值直接作为 JSON 返回给前端
public class HealthController {

    // Spring 自动装配好的数据库操作工具，构造器注入（推荐写法）
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * GET http://localhost:8080/api/health
     * 返回：服务状态、服务器时间、数据库版本、sys_user 表里的账号数
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        // 能查出来 = 数据源配置正确、库真的连上了
        String dbVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user", Integer.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("serverTime", LocalDateTime.now().toString());
        result.put("dbVersion", dbVersion);
        result.put("sysUserCount", userCount);
        return result;
    }
}
