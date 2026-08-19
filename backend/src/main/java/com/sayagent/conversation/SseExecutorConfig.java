package com.sayagent.conversation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 推送专用线程池（M6 T2，§4.6/§4.2/§8）。
 *
 * <p>大白话：每条聊天流都在独立线程上"逐片推送 token"，绝不能占用 Tomcat 处理 HTTP 的主线程，
 * 否则少量慢流就把网关堵死。这里用虚拟线程（Java 21）：一请求一虚拟线程，轻量且天然高并发。
 *
 * <p>为什么不复用 M3 的 {@code llmExecutor}：聊天流的推送节奏（10ms 一帧）与 LLM 生成语义不同，
 * 分开隔离便于排查与限流（§4.2 线程纪律：慢活互相隔离防头阻塞）。
 */
@Configuration
public class SseExecutorConfig {

    /** SSE 逐 token 推送线程池：虚拟线程，逐请求一轻量线程，不占 Tomcat 主线程（§4.6/§4.2）。 */
    @Bean("sseExecutor")
    public ExecutorService sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
