package com.hify.hify.modelprovider.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 全局单例（§4.8）。
 *
 * <p>大白话：所有 LLM 调用共用一个 OkHttpClient（带连接池 + 超时），禁止每次请求 new 一个，
 * 否则连接数爆炸。超时参数走配置（application.yml 的 hify.okhttp.*），不写死在代码里。
 */
@Configuration
public class OkHttpConfig {

    /** 连接池：最大空闲 32 连接，空闲 5 分钟回收。 */
    private static final int MAX_IDLE_CONNECTIONS = 32;
    private static final long KEEP_ALIVE_MINUTES = 5;

    @Value("${hify.okhttp.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${hify.okhttp.read-timeout-seconds:60}")
    private int readTimeoutSeconds;

    @Value("${hify.okhttp.write-timeout-seconds:30}")
    private int writeTimeoutSeconds;

    /**
     * 全局唯一 OkHttpClient Bean（连接池 + 连接/读/写超时）。
     *
     * @return 单例 OkHttpClient
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }
}
