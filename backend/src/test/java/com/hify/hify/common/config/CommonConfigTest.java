package com.hify.hify.common.config;

import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * common.config 配置类纯单元测试（不加载 Spring 上下文，无需数据库）。
 * 覆盖 T5 验收点里可离线验证的部分：llmExecutor 注入、Swagger、Cors 结构。
 */
class CommonConfigTest {

    @Test
    void testLlmExecutor_queueCapacity200_returnsBoundedQueueWithCallerRunsPolicy() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) new AsyncConfig().llmExecutor();
        assertTrue(executor.getQueue() instanceof LinkedBlockingQueue, "应使用有界 LinkedBlockingQueue（§7.5 规则20）");
        assertEquals(200, ((LinkedBlockingQueue<?>) executor.getQueue()).remainingCapacity(), "队列容量须为 200");
        assertEquals(ThreadPoolExecutor.CallerRunsPolicy.class,
                executor.getRejectedExecutionHandler().getClass(), "拒绝策略须为 CallerRunsPolicy 降级");
    }

    @Test
    void testEmbeddingAndRetrievalExecutors_createInstances_notNull() {
        assertNotNull(new AsyncConfig().embeddingExecutor(), "embeddingExecutor 应注入成功");
        assertNotNull(new AsyncConfig().retrievalExecutor(), "retrievalExecutor 应注入成功");
    }

    @Test
    void testSwaggerOpenApi_buildBean_titleIsHifyApi() {
        OpenAPI api = new SwaggerConfig().hifyOpenApi();
        assertNotNull(api);
        assertNotNull(api.getInfo());
        assertEquals("Hify API", api.getInfo().getTitle());
    }

    @Test
    void testCorsConfig_defaultOrigins_allows6177AndCredentials() throws Exception {
        CorsConfig config = new CorsConfig();
        // 模拟 Spring 注入 @Value 默认值（脱离容器时手动设置字段）
        Field field = CorsConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(config, "http://localhost:6177");

        CorsConfigurationSource source = config.corsConfigurationSource();
        assertTrue(source instanceof UrlBasedCorsConfigurationSource, "应为 UrlBasedCorsConfigurationSource");
        CorsConfiguration cors = ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");
        assertNotNull(cors, "应注册 /** 的跨域规则");
        assertTrue(cors.getAllowedOrigins().contains("http://localhost:6177"), "应放行前端来源");
        assertTrue(cors.getAllowCredentials(), "应允许携带凭证（cookie/token）");
    }
}
