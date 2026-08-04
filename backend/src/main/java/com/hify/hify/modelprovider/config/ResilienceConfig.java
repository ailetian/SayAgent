package com.hify.hify.modelprovider.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.hify.hify.common.exception.RetryPredicates;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Resilience4j 装配（§4.8 / §3.3 / §4.9）。
 *
 * <p>大白话：给每个厂商（OPENAI/CLAUDE/GEMINI/OLLAMA）各配一套独立的「保险丝」——
 * 熔断(CircuitBreaker)、重试(Retry)、超时(TimeLimiter)、舱壁(Bulkhead)。
 * 实例与阈值全部由 application.yml 的 resilience4j.* 段驱动（不写死在 Java）。
 * 本类把每家的状态切换/重试事件接到日志上，便于排查（§4.9 占位符，禁止 + 拼接）。
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    /** 熔断实例（按 provider_type 维度索引），并挂上状态切换日志。 */
    @Bean
    public Map<String, CircuitBreaker> providerCircuitBreakers(CircuitBreakerRegistry registry) {
        Map<String, CircuitBreaker> map = new HashMap<>();
        registry.getAllCircuitBreakers().forEach(cb -> {
            registerCircuitBreakerEvents(cb);
            map.put(cb.getName(), cb);
        });
        return map;
    }

    /** 重试实例（按 provider_type 维度索引），并挂上重试触发日志。 */
    @Bean
    public Map<String, Retry> providerRetries(RetryRegistry registry) {
        Map<String, Retry> map = new HashMap<>();
        registry.getAllRetries().forEach(retry -> {
            registerRetryEvents(retry);
            map.put(retry.getName(), retry);
        });
        return map;
    }

    /** 超时实例（按 provider_type 维度索引）。 */
    @Bean
    public Map<String, TimeLimiter> providerTimeLimiters(TimeLimiterRegistry registry) {
        Map<String, TimeLimiter> map = new HashMap<>();
        registry.getAllTimeLimiters().forEach(tl -> map.put(tl.getName(), tl));
        return map;
    }

    /** 舱壁实例（按 provider_type 维度索引）。 */
    @Bean
    public Map<String, Bulkhead> providerBulkheads(BulkheadRegistry registry) {
        Map<String, Bulkhead> map = new HashMap<>();
        registry.getAllBulkheads().forEach(b -> map.put(b.getName(), b));
        return map;
    }

    /**
     * 重试可恢复性（§4.4）：只对 429/5xx 重试，400/401/403 等 4xx 永不重试。
     * 作用于各模型供应商重试实例（实例名 = ProviderType），由 Spring 自动装配的
     * RetryRegistry bean 在创建时应用（ResilienceConfig 注入的即该 bean）。
     */
    @Bean
    public RetryConfigCustomizer retryConfigCustomizer() {
        // §4.4：只对 429/5xx 重试，400/401/403 等 4xx 永不重试。
        // RetryConfigCustomizer 非函数接口：需实现 name() 与 customize(Builder) 两个抽象方法。
        return new RetryConfigCustomizer() {
            @Override
            public String name() {
                return "retry-recoverable";
            }

            @Override
            public void customize(RetryConfig.Builder builder) {
                builder.retryOnException(RetryPredicates.RECOVERABLE);
            }
        };
    }

    private void registerCircuitBreakerEvents(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                log.warn("circuit-breaker opened: provider={}", cb.getName());
            } else {
                log.info("circuit-breaker transition: provider={} transition={}", cb.getName(), event.getStateTransition());
            }
        });
    }

    private void registerRetryEvents(Retry retry) {
        retry.getEventPublisher().onRetry(event ->
                log.info("retry triggered: provider={} attempt={}", retry.getName(), event.getNumberOfRetryAttempts()));
    }
}
