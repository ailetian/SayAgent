package com.sayagent.common.exception;

import com.sayagent.common.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常翻译「兜底员工」（§3.5 API 响应契约、§7.3 异常处理、§7.4 日志纪律）。
 *
 * <p>大白话：任何 Controller 里抛出的异常，都会被这里统一接住，装进
 * {@link Result} 这个统一盒子里返回给前端。前端永远拿到 {@code {code, data, message}}，
 * 不用为每种错误单独写适配；后端也不会再出现裸 500。
 *
 * <p>职责边界（§3.2 分层纪律）：本类只做「异常 → 统一响应」的翻译与日志，
 * 不含任何业务逻辑；真正的业务校验在 service 层用 {@link BizException} 主动抛出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：直接按错误码翻译成统一盒子。
     *
     * @param ex 业务异常（携带 ErrorCode）
     * @return 统一响应盒子（code 取错误码编号）
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        // 认证失败（用户名/密码错）映射到 HTTP 401；无权限映射到 HTTP 403（§7.11 服务层拦截，M7 规范）；
        // 其余业务异常仍走 HTTP 200 + 错误码（§3.5 统一盒子）。
        HttpStatus status = switch (ex.getErrorCode()) {
            case AUTH_FAIL -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.OK;
        };
        log.warn("biz.exception code={} msg={}", ex.getErrorCode().getCode(), ex.getMessage());
        // 透出异常自定义明细（如「被哪些 Agent 挂载」），而非仅枚举文案（§3.5 统一盒子细节可附）。
        // 注意：这里必须传 getDetail() 而不是 getMessage()——后者已含枚举文案前缀，
        // 再交给 Result.fail(ec, detail) 会把前缀拼第二遍（历史 bug：提示重复两段）。
        Result<Void> body = ex.getDetail() == null
                ? Result.fail(ex.getErrorCode())
                : Result.fail(ex.getErrorCode(), ex.getDetail());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Bean Validation 校验失败（Controller 入参上的 @NotNull/@Size 等没过）。
     * 把每个字段的错误拼成可读串，方便前端定位。
     *
     * @param ex 参数校验异常
     * @return 统一响应盒子（code = PARAM_INVALID 的编号）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("param.invalid {}", detail);
        return Result.fail(ErrorCode.PARAM_INVALID, detail);
    }

    /**
     * 资源/路径不存在（Spring Boot 3 找不到 handler 时抛 {@link NoResourceFoundException}，例如访问了不存在的 API）。
     * 这是客户端正常误请求，按 HTTP 404 返回，不再被兜底成 5000 系统异常（与验收问题2修复一致）。
     *
     * @param ex 资源未找到异常
     * @return 统一响应盒子（code = RESOURCE_NOT_FOUND 的编号，HTTP 404）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("resource.not.found {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 兜底：其余所有未被具体捕获的异常 → 系统异常，打印完整堆栈便于排查（§7.4 规则17）。
     *
     * @param ex 未知异常
     * @return 统一响应盒子（code = SYS_ERROR 的编号）
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception ex) {
        log.error("unhandled.exception", ex);
        return Result.fail(ErrorCode.SYS_ERROR);
    }

    /**
     * 把一个字段错误格式化为 {@code 字段名:提示} 形态。
     *
     * @param fieldError 字段级校验错误
     * @return 可读串
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ":" + fieldError.getDefaultMessage();
    }
}
