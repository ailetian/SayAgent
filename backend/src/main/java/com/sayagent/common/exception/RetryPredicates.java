package com.sayagent.common.exception;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * 重试可恢复性判定（§4.4：只重试可恢复错误）。
 *
 * <p>大白话：告诉 Resilience4j 的 Retry「哪种错值得再试一次」。
 * 仅当异常携带可恢复的 HTTP 状态码（429 或 5xx）时才重试；
 * 4xx 客户端错误（含 400/401/403/404）一律不重试；
 * 没有状态码的传输/超时/解析异常按「可恢复」处理（§4.4 超时/连接异常要重试）。
 */
public final class RetryPredicates {

    private RetryPredicates() {
    }

    /** 供 Retry 配置使用的谓词：返回 true 表示应重试。 */
    public static final Predicate<Throwable> RECOVERABLE = RetryPredicates::isRecoverable;

    /** 判断给定异常是否应触发重试（§4.4）。 */
    public static boolean isRecoverable(Throwable t) {
        Throwable cause = t;
        if (cause instanceof ExecutionException || cause instanceof CompletionException) {
            cause = cause.getCause() != null ? cause.getCause() : cause;
        }
        if (cause instanceof BizException be) {
            Integer status = be.getHttpStatus();
            if (status == null) {
                // 无状态码：传输/超时/解析/内部异常，按可恢复处理（§4.4 超时/连接异常要重试）
                return true;
            }
            // 429 与 5xx 可重试；4xx（含 400/401/403/404）永不重试
            return status == 429 || (status >= 500 && status < 600);
        }
        // 非 BizException（如原生超时）按可恢复处理
        return true;
    }
}
