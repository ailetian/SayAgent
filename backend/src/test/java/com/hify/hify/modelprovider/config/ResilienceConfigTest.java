package com.hify.hify.modelprovider.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResilienceConfig 单测（§7.10 命名 test方法_场景_预期）。
 *
 * <p>大白话：临时把 OPENAI 熔断阈值调到极低，连续打几次失败请求，验证熔断真的打开（CB 生效），
 * 并且熔断器状态切换被打印成 WARN 日志。禁用 Flyway 以便无需 MySQL 即可跑。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "resilience4j.circuitbreaker.instances.OPENAI.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.OPENAI.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.OPENAI.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.OPENAI.wait-duration-in-open-state=1000ms"
})
class ResilienceConfigTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void testCircuitBreaker_opensAfterFailuresAndLogs() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("OPENAI");

        Logger logger = (Logger) LoggerFactory.getLogger(ResilienceConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Supplier<String> decorated = CircuitBreaker.decorateSupplier(cb, () -> {
                throw new RuntimeException("boom");
            });
            for (int i = 0; i < 3; i++) {
                try {
                    decorated.get();
                } catch (Exception ignored) {
                    // 熔断打开后会抛 CallNotPermittedException，忽略
                }
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());

            boolean openedLogged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("opened"));
            assertTrue(openedLogged, "熔断打开应打印 WARN 日志");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
