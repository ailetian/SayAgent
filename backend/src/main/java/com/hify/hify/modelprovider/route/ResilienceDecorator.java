package com.hify.hify.modelprovider.route;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.tool.ToolDefinition;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.ProviderClient;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.domain.enums.ProviderType;

import reactor.core.publisher.Flux;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 把 T2 的 ProviderClient 用 T3 的「保险丝」包一层（§4.5 容错 / §4.3 超时）。
 *
 * <p>大白话：给每个厂商的 Client 套上 熔断(CB) + 重试(Retry) + 舱壁(Bulkhead) + 超时(TimeLimiter)
 * 四件套，实例名直接用 ProviderType（OPENAI/CLAUDE/...），阈值沿用 T3 在 application.yml 里的配置。
 * 包法拆小方法，保持低复杂度。
 */
@Component
public class ResilienceDecorator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public ResilienceDecorator(CircuitBreakerRegistry circuitBreakerRegistry,
                               RetryRegistry retryRegistry,
                               TimeLimiterRegistry timeLimiterRegistry,
                               BulkheadRegistry bulkheadRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    /** 包成带 4 种治理的装饰实例（实例名=ProviderType，沿用 T3 配置）。 */
    public ProviderClient decorate(ProviderClient delegate, ProviderType type) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(type.name());
        Retry retry = retryRegistry.retry(type.name());
        TimeLimiter tl = timeLimiterRegistry.timeLimiter(type.name());
        Bulkhead bh = bulkheadRegistry.bulkhead(type.name());
        return new ResilientClient(delegate, cb, retry, tl, bh);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    /** 装饰实例：把四件套按顺序套在 delegate.send 外面，超时最外层（§4.3）。 */
    private class ResilientClient implements ProviderClient {
        private final ProviderClient delegate;
        private final CircuitBreaker cb;
        private final Retry retry;
        private final TimeLimiter tl;
        private final Bulkhead bh;

        ResilientClient(ProviderClient delegate, CircuitBreaker cb, Retry retry, TimeLimiter tl, Bulkhead bh) {
            this.delegate = delegate;
            this.cb = cb;
            this.retry = retry;
            this.tl = tl;
            this.bh = bh;
        }

        @Override
        public ProviderType getType() {
            return delegate.getType();
        }

        @Override
        public List<float[]> embed(List<String> texts, ProviderConfig config) {
            Supplier<List<float[]>> supplier = () -> delegate.embed(texts, config);
            Supplier<List<float[]>> resilient = Bulkhead.decorateSupplier(bh,
                    Retry.decorateSupplier(retry,
                            CircuitBreaker.decorateSupplier(cb, supplier)));
            Supplier<Future<List<float[]>>> futureSupplier =
                    () -> CompletableFuture.supplyAsync(resilient::get, scheduler);
            Callable<List<float[]>> timed = TimeLimiter.decorateFutureSupplier(tl, futureSupplier);
            try {
                return timed.call();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        @Override
        public LlmResponse send(List<ChatMessage> messages, ProviderConfig config) {
            Supplier<LlmResponse> supplier = () -> delegate.send(messages, config);
            Supplier<LlmResponse> resilient = Bulkhead.decorateSupplier(bh,
                    Retry.decorateSupplier(retry,
                            CircuitBreaker.decorateSupplier(cb, supplier)));
            Supplier<Future<LlmResponse>> futureSupplier =
                    () -> CompletableFuture.supplyAsync(resilient::get, scheduler);
            Callable<LlmResponse> timed = TimeLimiter.decorateFutureSupplier(tl, futureSupplier);
            try {
                return timed.call();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        @Override
        public Flux<String> stream(List<ChatMessage> messages, ProviderConfig config, TokenUsage usage) {
            // 流式输出不套 CB/Retry/Bulkhead（Flux 流式场景无原生装饰器，且逐 token 取消由订阅方负责），直接透传。
            return delegate.stream(messages, config, usage);
        }

        @Override
        public LlmResponse send(List<ChatMessage> messages, ProviderConfig config,
                                List<ToolDefinition> tools) {
            // 带工具的函数调用同样套四件套治理（§4.5），避免默认接口方法回退到无工具调用
            Supplier<LlmResponse> supplier = () -> delegate.send(messages, config, tools);
            Supplier<LlmResponse> resilient = Bulkhead.decorateSupplier(bh,
                    Retry.decorateSupplier(retry,
                            CircuitBreaker.decorateSupplier(cb, supplier)));
            Supplier<Future<LlmResponse>> futureSupplier =
                    () -> CompletableFuture.supplyAsync(resilient::get, scheduler);
            Callable<LlmResponse> timed = TimeLimiter.decorateFutureSupplier(tl, futureSupplier);
            try {
                return timed.call();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }
    }

    /** 剥掉 Future/包装异常，让底层 BizException(LLM_CALL_FAILED) 等原样上浮给路由层（§7.4）。 */
    private RuntimeException unwrap(Exception e) {
        Throwable cause = e;
        if (e instanceof ExecutionException || e instanceof CompletionException) {
            cause = e.getCause() != null ? e.getCause() : e;
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new BizException(ErrorCode.LLM_CALL_FAILED, "llm call failed: " + cause.getMessage());
    }
}
