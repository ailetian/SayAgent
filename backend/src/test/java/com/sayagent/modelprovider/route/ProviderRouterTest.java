package com.sayagent.modelprovider.route;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.common.exception.RetryPredicates;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.client.ProviderClient;
import com.sayagent.modelprovider.client.ProviderConfig;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.entity.ModelProvider;
import com.sayagent.modelprovider.repository.ModelProviderRepository;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import java.time.Duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderRouter 路由 + 降级链 + 可观测点检（纯单元，mock 仓库/装饰器/Client，无需 MySQL）。
 *
 * <p>大白话：
 * <ul>
 *   <li>主模型（OPENAI）抛异常时，应自动降级到下一个 enabled 的 Provider（CLAUDE）并返回成功；</li>
 *   <li>收到 429（BizException）时，复用 T3 Retry 自动重试直到成功；</li>
 *   <li>每次成功调用应打一条含 6 字段的 INFO 日志，且日志里无秘钥（T6）；</li>
 *   <li>全部失败 / 无启用模型 → 抛 LLM_ALL_PROVIDERS_FAILED。</li>
 * </ul>
 *
 * <p>命名遵循 test方法_场景_预期（AGENTS.md §7.10 规则34）。
 */
@ExtendWith(MockitoExtension.class)
class ProviderRouterTest {

    @Mock
    private ModelProviderRepository repository;

    @Mock
    private ResilienceDecorator decorator;

    @Mock
    private ProviderClient primary;   // OPENAI

    @Mock
    private ProviderClient secondary; // CLAUDE

    private ProviderRouter router;

    @BeforeEach
    void setUp() {
        when(primary.getType()).thenReturn(ProviderType.OPENAI);
        when(secondary.getType()).thenReturn(ProviderType.CLAUDE);
        // 装饰器透传，让多数测试聚焦路由/降级逻辑（治理的 429 重试在专用用例里用真实装饰器）。
        // lenient：无启用模型时 route 提前抛错、不会走到 decorate，严格 stubbing 会报 UnnecessaryStubbing。
        lenient().when(decorator.decorate(any(), any())).thenAnswer(inv -> (ProviderClient) inv.getArgument(0));
        router = new ProviderRouter(repository, decorator, List.of(primary, secondary));
    }

    @Test
    void testProviderRouter_primaryFails_fallsBackToNext() {
        ModelProvider p1 = provider(1L, ProviderType.OPENAI, true, 0);
        ModelProvider p2 = provider(2L, ProviderType.CLAUDE, false, 1);
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(p1, p2));

        when(primary.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=OPENAI status=500"));
        LlmResponse ok = LlmResponse.builder().content("hi from claude").build();
        when(secondary.send(any(), any())).thenReturn(ok);

        LlmResponse resp = router.route(List.of());

        assertEquals("hi from claude", resp.getContent());
        verify(primary).send(any(), any());
        verify(secondary).send(any(), any());
    }

    @Test
    void testProviderRouter_receive429_retriesThenSuccess() {
        // 用与 T3 生产一致的重试配置（maxAttempts=3, waitDuration=500ms，且按 §4.4 谓词只重试 429/5xx）
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryOnException(RetryPredicates.RECOVERABLE)
                .build();
        ResilienceDecorator realDeco = new ResilienceDecorator(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(retryConfig),
                TimeLimiterRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults());

        ProviderClient client = org.mockito.Mockito.mock(ProviderClient.class);
        when(client.getType()).thenReturn(ProviderType.OPENAI);
        LlmResponse ok = LlmResponse.builder().content("ok after retry")
                .promptTokens(1).completionTokens(2).rawStatus(200).build();
        // 第一次返回 429（BizException，带 httpStatus=429），第二次成功；谓词判定 429 可重试，故只调 2 次。
        when(client.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=OPENAI status=429", 429))
                .thenReturn(ok);

        ProviderRouter retryRouter = new ProviderRouter(repository, realDeco, List.of(client));
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc())
                .thenReturn(List.of(provider(1L, ProviderType.OPENAI, true, 0)));

        LlmResponse resp = retryRouter.route(List.of());

        assertEquals("ok after retry", resp.getContent());
        verify(client, times(2)).send(any(), any());
    }

    @Test
    void testProviderRouter_receive401_doesNotRetry() {
        // §4.4：401 属于 4xx 客户端错误，重试谓词应判定「不重试」，只调 1 次并直接失败。
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryOnException(RetryPredicates.RECOVERABLE)
                .build();
        ResilienceDecorator realDeco = new ResilienceDecorator(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(retryConfig),
                TimeLimiterRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults());

        ProviderClient client = org.mockito.Mockito.mock(ProviderClient.class);
        when(client.getType()).thenReturn(ProviderType.OPENAI);
        // 401 不可重试，不会走到第二次，故用 thenThrow 即可。
        when(client.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=OPENAI status=401", 401));

        ProviderRouter retryRouter = new ProviderRouter(repository, realDeco, List.of(client));
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc())
                .thenReturn(List.of(provider(1L, ProviderType.OPENAI, true, 0)));

        assertThrows(BizException.class, () -> retryRouter.route(List.of()));
        verify(client, times(1)).send(any(), any());
    }

    @Test
    void testProviderRouter_allProvidersFail_throwsAllProvidersFailed() {
        ModelProvider p1 = provider(1L, ProviderType.OPENAI, true, 0);
        ModelProvider p2 = provider(2L, ProviderType.CLAUDE, false, 1);
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(p1, p2));
        when(primary.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=OPENAI failed"));
        when(secondary.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=CLAUDE failed"));

        BizException ex = assertThrows(BizException.class, () -> router.route(List.of()));

        assertEquals(ErrorCode.LLM_ALL_PROVIDERS_FAILED, ex.getErrorCode());
    }

    @Test
    void testProviderRouter_noEnabledProvider_throwsAllProvidersFailed() {
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> router.route(List.of()));

        assertEquals(ErrorCode.LLM_ALL_PROVIDERS_FAILED, ex.getErrorCode());
    }

    @Test
    void testProviderRouter_success_logsInfoWithSevenFields() {
        ModelProvider p1 = provider(1L, ProviderType.OPENAI, true, 0);
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(p1));
        LlmResponse ok = LlmResponse.builder().content("hi")
                .promptTokens(10).completionTokens(5).rawStatus(200).build();
        when(primary.send(any(), any())).thenReturn(ok);

        Logger logger = (Logger) LoggerFactory.getLogger(ProviderRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            router.route(List.of());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertEquals(1, infos.size(), "应恰好有一条 INFO 调用日志");
        String msg = infos.get(0).getFormattedMessage();
        // §4.9 固定 7 字段：provider / model / costMs / inTok / outTok / fallback / ok
        assertTrue(msg.contains("provider=OPENAI"), "缺 provider");
        assertTrue(msg.contains("model=model-OPENAI"), "缺 model");
        assertTrue(msg.contains("costMs="), "缺 costMs");
        assertTrue(msg.contains("inTok=10"), "缺 inTok");
        assertTrue(msg.contains("outTok=5"), "缺 outTok");
        assertTrue(msg.contains("fallback=false"), "缺 fallback（主调用应为 false）");
        assertTrue(msg.contains("ok=true"), "缺 ok（成功应为 true）");
        assertFalse(msg.contains("secret"), "日志不得含 secret");
        assertFalse(msg.contains("apiKey"), "日志不得含 apiKey");
        assertFalse(msg.contains("sk-"), "日志不得含秘钥明文");
    }

    @Test
    void testProviderRouter_fallback_logsInfoWithFallbackFlag() {
        ModelProvider p1 = provider(1L, ProviderType.OPENAI, true, 0);
        ModelProvider p2 = provider(2L, ProviderType.CLAUDE, false, 1);
        when(repository.findAllByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(p1, p2));
        when(primary.send(any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED, "provider=OPENAI status=500"));
        LlmResponse ok = LlmResponse.builder().content("hi from claude")
                .promptTokens(7).completionTokens(3).rawStatus(200).build();
        when(secondary.send(any(), any())).thenReturn(ok);

        Logger logger = (Logger) LoggerFactory.getLogger(ProviderRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            router.route(List.of());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // 主调用失败：一条 INFO(ok=false, fallback=false)；降级成功：一条 INFO(ok=true, fallback=true)
        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertEquals(2, infos.size(), "失败 + 降级应各一条 INFO");
        String failed = infos.get(0).getFormattedMessage();
        String succeed = infos.get(1).getFormattedMessage();
        assertTrue(failed.contains("ok=false"), "失败 INFO 应 ok=false");
        assertTrue(failed.contains("fallback=false"), "失败发生在主调用，fallback 应为 false");
        assertTrue(succeed.contains("ok=true"), "降级成功 INFO 应 ok=true");
        assertTrue(succeed.contains("fallback=true"), "降级成功 INFO 应 fallback=true");
    }

    private ModelProvider provider(Long id, ProviderType type, boolean isDefault, int sortOrder) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setName("p-" + type);
        p.setApiUrl("https://" + type.name().toLowerCase() + ".example.com");
        p.setSecret("secret-" + type);
        p.setModel("model-" + type);
        p.setProviderType(type);
        p.setEnabled(true);
        p.setDefaultModel(isDefault);
        p.setSortOrder(sortOrder);
        return p;
    }
}
