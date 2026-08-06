package com.hify.hify.knowledge.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * 索引流水线专用线程池（K6）。
 *
 * <p>大白话：解析文档（CPU 密集）和向量化（IO 密集、调外部 embedding 服务）是两件性质完全不同的慢活，
 * 必须各用各的池子互相隔离（背压 §4.2 / §7.5 规则20），否则一批大文件会把整条索引链路堵死。
 *
 * <ul>
 *   <li>{@code parseExecutor}：解析 + 切片（CPU 密集），本配置新建，核心 2~4。
 *   <li>{@code embeddingExecutor}：向量化（IO 密集），<b>复用</b> M1 {@code AsyncConfig} 已存在的池子
 *       （"复用 M1 AsyncConfig 风格"），不重复建第二个 embedding 池。</li>
 * </ul>
 *
 * <p>两个池都用<b>有界队列 + CallerRunsPolicy</b>：队列满时由提交方（Web 线程）自己顶上跑，
 * 既做背压又不无限堆积 OOM（§7.5 规则20）。解析池满 → 上传请求所在线程帮忙跑解析，
 * 自然限制并发上传量。
 */
@Configuration
public class IndexingExecutorConfig {

    /** 解析/切片池：核心 2、最大 4、队列 50，CallerRunsPolicy 背压。 */
    @Bean("parseExecutor")
    public ExecutorService parseExecutor() {
        ThreadFactory factory = new CustomizableThreadFactory("hify-parse-");
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
