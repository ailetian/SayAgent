package com.sayagent.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 专用配置（M7/T2，§4.2 线程管理 / §4.3 超时 / §7.5 并发）。
 *
 * <p>大白话：MCP 调用是「连内部慢系统」的远程调用，必须单独开一个线程池（mcpExecutor），
 * 不能和 LLM 调用抢同一个池（防头阻塞，§4.2）；同时把连接/读超时集中在这里，方便在
 * application.yml 里调（§4.3 两级超时）。超时默认值也写在这里，改配置不动代码（§7.2 规则7）。
 */
@Configuration
public class McpConfig {

    /** 连接超时（毫秒），对应 §4.3 两级超时——连不上就快点断，不无限等。 */
    @Value("${mcp.connect-timeout-ms:3000}")
    int connectTimeoutMs = 3000;

    /** 读超时（毫秒），单次工具调用的整体响应预算。 */
    @Value("${mcp.read-timeout-ms:30000}")
    int readTimeoutMs = 30000;

    /** 专用线程池核心线程数。 */
    @Value("${mcp.executor.core:4}")
    private int executorCore = 4;

    /** 专用线程池最大线程数。 */
    @Value("${mcp.executor.max:8}")
    private int executorMax = 8;

    /** 专用线程池有界队列长度（防无界队列 OOM，§7.5 规则20）。 */
    @Value("${mcp.executor.queue:100}")
    private int executorQueue = 100;

    /**
     * MCP 专用线程池（有界队列 + 命名线程 + CallerRunsPolicy 拒绝策略）。
     *
     * <p>调用方（T3 ConversationService）必须用 {@code CompletableFuture.supplyAsync(task, mcpExecutor)}
     * 把 MCP 调用放到这个池里跑，<b>禁止在数据库事务内调用</b>（§4.2 / §7.5 规则23）。
     *
     * @return 专用 ExecutorService
     */
    @Bean(name = "mcpExecutor")
    public ExecutorService mcpExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mcp-pool-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(
                executorCore, executorMax, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(executorQueue), threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
