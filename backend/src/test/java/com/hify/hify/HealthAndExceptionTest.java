package com.hify.hify;

import com.hify.hify.common.Result;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.user.AdminSeedRunner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1/T7 启动联调与异常翻译单测（§7.10 规则34/35）。
 *
 * <p>大白话：这是 M1 的"出厂检验"——证明两件事：
 * <ol>
 *   <li>整个 Spring 大容器能正常启动（{@code HifyApplication} 上下文就绪，核心 Bean 都在位）。</li>
 *   <li>Controller 抛业务异常时，{@link com.hify.hify.common.exception.GlobalExceptionHandler}
 *       能把它翻译成统一盒子 {@code {code, message, data}}，且 {@code code != 0}。</li>
 * </ol>
 *
 * <p>不依赖真实数据库/Redis（§7.10 规则35）：
 * <ul>
 *   <li>关闭 Flyway（{@code spring.flyway.enabled=false}），不在测试里跑迁移。</li>
 *   <li>不再排除 JPA：T5 起 AuthController/AuthService/CustomUserDetailsService 等 Bean 依赖 UserRepository，
 *       排除 JPA 反而会导致它们无法注册；这些 Bean 属"懒连接"，Bean 创建时不真正连库。</li>
 *   <li>唯一会在启动时触库的是 {@code AdminSeedRunner}（播种 admin），已用 {@code @MockBean} 顶掉，故无库也能跑。</li>
 *   <li>DataSource/Redis 连接池是"懒连接"，上下文可无库加载。</li>
 * </ul>
 * 
 * <p>测试方法命名遵循 {@code test方法_场景_预期}（AGENTS.md §7.10 规则34）。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(HealthAndExceptionTest.TestBizController.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false"
})
class HealthAndExceptionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    // T5 起存在依赖 UserRepository 的 Bean（AuthController/AuthService/CustomUserDetailsService 等），
    // 这些 Bean 懒加载不连库即能注册（见 SecurityConfigTest 同款上下文）。
    // 唯一会在启动时触库的是 AdminSeedRunner（播种 admin），用 mock 顶掉它即可"不连真实库"跑本测试。
    @MockBean
    private AdminSeedRunner adminSeedRunner;

    /**
     * 验证 {@code HifyApplication} 整个 Spring 上下文能启动，且 M1 核心 Bean 就绪。
     *
     * <p>预期：上下文非空，全局异常处理器、MySQL 主数据源、Redis 模板等关键 Bean 均已注册。
     */
    @Test
    void testApplicationContext_loads_isReady() {
        assertNotNull(applicationContext, "Spring 上下文应启动成功");
        assertTrue(applicationContext.containsBean("globalExceptionHandler"),
                "全局异常处理器 GlobalExceptionHandler 应就绪");
        assertTrue(applicationContext.containsBean("dataSource"),
                "MySQL 主数据源 dataSource 应就绪");
        assertTrue(applicationContext.containsBean("pgDataSource"),
                "PostgreSQL 向量库数据源 pgDataSource 应就绪");
        assertTrue(applicationContext.containsBean("redisTemplate"),
                "Redis 模板 redisTemplate 应就绪");
        assertTrue(applicationContext.containsBean("sayAgentOpenApi"),
                "Swagger OpenAPI 文档 Bean 应就绪");
    }

    /**
     * 验证 Controller 抛 {@link BizException} 时，被 {@code GlobalExceptionHandler} 翻译成
     * 统一响应体 {@code {code, message, data}}，且 {@code code != 0}。
     *
     * <p>预期：HTTP 200，响应体 {@code code=1001}（MODEL_NOT_FOUND）、{@code message="模型不存在"}、
     * {@code data=null}。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    void testBizException_thrownFromController_returnsUnifiedFailBodyWithNonZeroCode() throws Exception {
        int expectedCode = ErrorCode.MODEL_NOT_FOUND.getCode();
        assertTrue(expectedCode != 0, "校验用的错误码本身须非 0，才能证明 code≠0");

        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(ErrorCode.MODEL_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * 测试专用 stub 控制器：命中即抛 {@link BizException}，用来触发全局异常翻译。
     *
     * <p>通过 {@link Import} 注册成 Bean（带 {@code @RestController} 注解，
     * 故会被 {@code RequestMappingHandlerMapping} 识别为处理器）。
     */
    @RestController
    static class TestBizController {

        @GetMapping("/test/biz")
        public Result<String> biz() {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
    }
}
