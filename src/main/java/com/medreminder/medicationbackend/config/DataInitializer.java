package com.medreminder.medicationbackend.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medreminder.medicationbackend.entity.User;
import com.medreminder.medicationbackend.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动初始化器。
 * 建库时 sys_user 里 admin 的 password_hash 是占位符（不是真实 BCrypt 哈希），
 * 首次启动检测到占位符就重置为 Admin@123 的真实 BCrypt 哈希，
 * 之后每次启动不会再触发。对应 schema.sql 注释里「首次登录时强制修改」的约定。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String ADMIN_DEFAULT_PASSWORD = "Admin@123";

    private final UserMapper userMapper;

    public DataInitializer(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void run(String... args) {
        User admin = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getAccount, "admin"));
        if (admin != null && admin.getPasswordHash() != null
                && admin.getPasswordHash().contains("PLACEHOLDER")) {
            admin.setPasswordHash(new BCryptPasswordEncoder().encode(ADMIN_DEFAULT_PASSWORD));
            userMapper.updateById(admin);
            log.info("[初始化] admin 管理员密码已重置为默认口令 {}", ADMIN_DEFAULT_PASSWORD);
        }
    }
}
