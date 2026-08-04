package com.hify.hify.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码保险柜：全公司统一用 BCrypt（§7.11 敏感字段不明文存）。
 * 强度因子用默认 10，足够内部 50 人规模；未来可调 strength 但需重算旧密文。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
