package com.sayagent.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;

/**
 * 门禁制度白皮书（§7.11 权限在服务层也要核，这里是第一道闸）。
 * - 登录接口免卡；其余 /api/** 必须亮有效卡（由 AuthFilter 验）。
 * - Session 用无状态（JWT 自带状态，不必服务端存会话）。
 * - 开启 @EnableMethodSecurity，使 @PreAuthorize("hasRole('ADMIN')") 生效。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthFilter authFilter;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)          // 内部 API + JWT 无状态，关 CSRF
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))   // 无 token / 未登录 → 401（而非默认 403）
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()  // 登录窗口免卡
                .requestMatchers("/actuator/**", "/swagger-ui.html", "/swagger-ui/**",
                    "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                .anyRequest().authenticated()                                    // 其余一律要卡
            )
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class); // 闸机装在大门前
        return http.build();
    }
}
