package com.sayagent.conversation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话日志异步落库专用线程池（M6 T4）。
 *
 * <p>大白话：写 conversation_log 的活儿不能在 Tomcat / SSE 主线程上做（会拖慢逐字推送），
 * 所以单独起一个虚拟线程池 {@code logExecutor}，与 T2 的 {@code sseExecutor} 隔离
 * （§4.2 线程纪律：慢活互相隔离防头阻塞）。虚拟线程轻量、一任务一线程，适合"提交即忘"的日志落库（§8 异步落库）。
 */
@Configuration
public class LogWriteConfig {

    /** 日志落库线程池：虚拟线程，不占 Tomcat / SSE 主线程，与 sseExecutor 隔离。 */
    @Bean("logExecutor")
    public ExecutorService logExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
