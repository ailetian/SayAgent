package com.sayagent.common;

import com.sayagent.common.exception.ErrorCode;

import lombok.Getter;

/**
 * 统一响应体「快递盒」（§3.5 API 响应契约）。
 *
 * <p>大白话：这是全公司所有接口统一的返回格式——不管成功失败，前端拿到的永远是
 * {@code { "code": 0, "data": ..., "message": "ok" }} 这种固定盒子。
 * {@code code=0} 表示成功；非 0 表示业务错误，具体含义看 {@link ErrorCode}。
 *
 * <p>为什么需要它：前端只需写一套解析逻辑，不用为每个接口单独适配字段差异；
 * 配合 {@code GlobalExceptionHandler}，后端任何异常都会自动装进这个盒子，不会出现「裸 500」。
 *
 * @param <T> 业务数据类型（成功时装在 {@code data} 里）
 */
@Getter
public class Result<T> {

    /** 业务状态码：0=成功，非 0=业务错误（见 ErrorCode）。 */
    private final int code;

    /** 业务数据：成功时才有值，失败时为 null。 */
    private final T data;

    /** 提示信息：成功为 "ok"，失败为错误描述。 */
    private final String message;

    private Result(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    /**
     * 成功（带业务数据）。
     *
     * @param data 要返回给前端的业务对象
     * @param <T>  数据类型
     * @return 统一成功的盒子
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, data, "ok");
    }

    /**
     * 成功（无业务数据，例如「删除成功」这类操作）。
     *
     * @param <T> 数据类型（此处为 Void）
     * @return 统一成功的盒子
     */
    public static <T> Result<T> ok() {
        return new Result<>(0, null, "ok");
    }

    /**
     * 失败（按错误码枚举生成，禁止魔法数字，§7.2 规则7）。
     *
     * @param ec 错误码枚举（集中定义，杜绝散落数字）
     * @param <T> 数据类型
     * @return 统一失败的盒子
     */
    public static <T> Result<T> fail(ErrorCode ec) {
        return new Result<>(ec.getCode(), null, ec.getMessage());
    }

    /**
     * 失败（错误码 + 自定义补充信息，例如把校验失败的具体字段拼上）。
     *
     * @param ec     错误码枚举
     * @param detail 补充细节
     * @param <T>    数据类型
     * @return 统一失败的盒子
     */
    public static <T> Result<T> fail(ErrorCode ec, String detail) {
        return new Result<>(ec.getCode(), null, ec.getMessage() + "：" + detail);
    }
}
