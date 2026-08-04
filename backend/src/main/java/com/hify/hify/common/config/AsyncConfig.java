package com.hify.hify.common.config;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * 专用线程池（§4.2 线程纪律，M1/T5 预留，M3/M5 接入）。
 *
 * <p>大白话：调 LLM / 向量化 / 检索都很慢，绝不能占用 Tomcat 处理 Web 请求的主线程，
 * 否则少量慢调用就把整个网站"堵死"。这里提前备好三套互相隔离的"搬运工团队"，
 * 每种慢活儿各用各的池、互不影响（防头阻塞）。
 *
 * <p>为什么用显式 {@link ThreadPoolExecutor} 而非 {@code Executors.newFixed*}：
 * 后者用无界队列，任务积压时会 OOM（§7.5 规则 20）。这里用有界队列 + 拒绝策略降级。
 */
@Configuration
public class AsyncConfig {

    /** LLM 生成：主对话链路，并发最高，队列给 200（§4.2 模板）。 */
    @Bean("llmExecutor")
    public Executor llmExecutor() {
        ThreadFactory factory = new CustomizableThreadFactory("hify-llm-");
        return new ThreadPoolExecutor(
                8, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Embedding 向量化：文档入库用，IO 密集，队列给 100。 */
    @Bean("embeddingExecutor")
    public Executor embeddingExecutor() {
        ThreadFactory factory = new CustomizableThreadFactory("hify-embed-");
        return new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** RAG 检索：查向量库，偏 CPU/网络，队列给 100。 */
    @Bean("retrievalExecutor")
    public Executor retrievalExecutor() {
        ThreadFactory factory = new CustomizableThreadFactory("hify-retrieval-");
        return new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
