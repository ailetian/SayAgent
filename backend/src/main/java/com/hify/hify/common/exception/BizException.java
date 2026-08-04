package com.hify.hify.common.exception;

import lombok.Getter;

/**
 * 业务异常（§7.3 规则14：继承 RuntimeException，命名 *Exception 且带 message）。
 *
 * <p>大白话：这是业务层「主动喊停」用的异常——比如查不到模型、用户没权限时，
 * 业务代码就 {@code throw new BizException(ErrorCode.MODEL_NOT_FOUND)}。
 * 它会被 {@link GlobalExceptionHandler} 自动翻译成统一响应盒 {@code Result<T>}，
 * 不会变成吓人的 500 裸错。
 *
 * <p>设计要点：
 * <ul>
 *   <li>继承 {@link RuntimeException}，所以业务方法签名上不用写 {@code throws}，调用方不被迫 try/catch。</li>
 *   <li>强制绑定一个 {@link ErrorCode}，从源头杜绝「裸字符串 + 魔法数字」。</li>
 * </ul>
 */
@Getter
public class BizException extends RuntimeException {

    /** 对应的错误码（GlobalExceptionHandler 据此决定响应里的 code / message）。 */
    private final ErrorCode errorCode;

    /**
     * 可选 HTTP 状态码（可空）。仅当异常由上游 HTTP 响应映射而来时填充（例如 LLM 调用
     * 返回 429/5xx），用于重试策略判断是否可恢复（§4.4：400/401/403 永不重试，429/5xx 重试）。
     */
    private final Integer httpStatus;

    /**
     * 仅带错误码（用枚举里登记的默认描述）。
     *
     * @param errorCode 错误码枚举
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.httpStatus = null;
    }

    /**
     * 带错误码 + 补充细节（例如把具体 ID 拼进信息里，方便排查）。
     *
     * @param errorCode 错误码枚举
     * @param detail    补充细节
     */
    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + "：" + detail);
        this.errorCode = errorCode;
        this.httpStatus = null;
    }

    /**
     * 带错误码 + 补充细节 + HTTP 状态码（供重试策略判断是否可恢复，§4.4）。
     *
     * @param errorCode 错误码枚举
     * @param detail    补充细节
     * @param httpStatus 上游 HTTP 状态码（可空）
     */
    public BizException(ErrorCode errorCode, String detail, Integer httpStatus) {
        super(errorCode.getMessage() + "：" + detail);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
