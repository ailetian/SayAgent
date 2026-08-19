package com.sayagent.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc，M1 里程碑要用的 {@code /swagger-ui.html} 页面）。
 *
 * <p>大白话：springdoc 会自动扫描所有 {@code @RestController} 生成在线接口文档，
 * 这里只补一个"文档首页"的基本信息（标题/版本/描述），让页面更友好。
 * 生产环境可按需在 {@code application.yml} 里关闭 UI（{@code springdoc.swagger-ui.enabled=false}）。
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI sayAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SayAgent API")
                        .description("给团队自己用的 AI 员工制造厂 —— 后端接口文档")
                        .version("v0.1"));
    }
}
