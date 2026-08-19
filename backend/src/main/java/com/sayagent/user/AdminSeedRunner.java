package com.sayagent.user;

import com.sayagent.common.security.PasswordEncoderConfig;
import com.sayagent.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 默认管理员「播种器」（应用启动即跑一次，§9 运维约定）。
 *
 * <p>大白话：为了让 Demo 开箱即用，应用一启动就检查库里有没有 {@code admin} 账号；
 * 没有就种一个（密码来自配置 {@code sayagent.admin.password}，默认 {@code admin123}，生产用
 * {@code SAYAGENT_ADMIN_PASSWORD} 环境变量覆盖）。已存在则跳过，绝不重复插入。
 *
 * <p>密码安全：存的是 BCrypt 密文，不是明文（§7.11）。密文通过
 * {@link PasswordEncoderConfig} 这个保险柜现编现用。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminSeedRunner implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "admin";

    private final UserRepository userRepository;
    private final PasswordEncoderConfig passwordEncoderConfig;

    @Value("${sayagent.admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.findByUsernameAndDeletedFalse(ADMIN_USERNAME).isPresent()) {
            log.info("admin user already exists, skip seeding");
            return;
        }
        String encoded = passwordEncoderConfig.passwordEncoder().encode(adminPassword);
        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword(encoded);
        admin.setRole(UserRole.ADMIN);
        admin.setDeleted(false);
        userRepository.save(admin);
        log.info("seeded default admin user '{}'", ADMIN_USERNAME);
    }
}
