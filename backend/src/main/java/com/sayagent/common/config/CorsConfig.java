package com.sayagent.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 全局跨域配置（§5.1 本地也要放开）。
 *
 * <p>大白话：浏览器出于安全会禁止"前端网页（A 端口）直接调后端（B 端口）"，
 * 这在本地前后端分离开发时是常态。这里统一放行前端来源，避免开发期一直被 CORS 拦截。
 */
@Configuration
public class CorsConfig {

    /** 允许的前端来源，可改 application.yml 的 {@code sayagent.cors.allowed-origins} 调整（逗号分隔）。 */
    @Value("${sayagent.cors.allowed-origins:http://localhost:6177}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
