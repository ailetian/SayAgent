package com.sayagent.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sayagent.common.Result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 全局异常翻译器单测（§3.5 统一响应契约）。
 *
 * <p>大白话：这里守住三件事——
 * <ol>
 *   <li>业务异常带的「补充细节」（例如「被哪些 Agent 挂载」）必须原样透出给前端，不能被吞；</li>
 *   <li>透出时枚举文案只能出现一次，<b>不许拼两遍</b>（历史 bug 回归位）；</li>
 *   <li>认证/权限类错误要落到正确的 HTTP 状态码，其余业务错误仍走 HTTP 200 + 错误码。</li>
 * </ol>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("只带错误码：message 就是枚举文案本身")
    void handleBiz_codeOnly_usesEnumMessage() {
        ResponseEntity<Result<Void>> resp = handler.handleBiz(new BizException(ErrorCode.MODEL_NOT_FOUND));

        Result<Void> body = resp.getBody();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(ErrorCode.MODEL_NOT_FOUND.getCode(), body.getCode());
        assertEquals(ErrorCode.MODEL_NOT_FOUND.getMessage(), body.getMessage());
    }

    @Test
    @DisplayName("带补充细节：细节必须透出，且枚举文案只拼一次（防重复前缀回归）")
    void handleBiz_withDetail_prefixAppearsExactlyOnce() {
        String detail = "「公司内部知识库」当前挂载方：验收客服Agent";
        ResponseEntity<Result<Void>> resp =
                handler.handleBiz(new BizException(ErrorCode.KNOWLEDGE_BASE_IN_USE, detail));

        String message = resp.getBody().getMessage();
        String enumText = ErrorCode.KNOWLEDGE_BASE_IN_USE.getMessage();

        // 细节透出：调用方能看到「被哪个 Agent 挂载」
        assertTrue(message.contains(detail), "补充细节应原样透出，实际=" + message);
        // 关键回归：枚举文案只能出现一次。曾经 handler 传的是 ex.getMessage()（已含前缀），
        // 再被 Result.fail(ec, detail) 拼一次，导致前端看到两段一模一样的提示。
        assertEquals(1, countOccurrences(message, enumText), "枚举文案应只出现一次，实际=" + message);
        assertEquals(enumText + "：" + detail, message);
    }

    @Test
    @DisplayName("认证失败 → HTTP 401；无权限 → HTTP 403")
    void handleBiz_authAndForbidden_mapToHttpStatus() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleBiz(new BizException(ErrorCode.AUTH_FAIL)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                handler.handleBiz(new BizException(ErrorCode.FORBIDDEN)).getStatusCode());
    }

    @Test
    @DisplayName("兜底异常 → 系统错误码，且不泄漏原始异常文案")
    void handleOther_fallsBackToSysError() {
        Result<Void> body = handler.handleOther(new IllegalStateException("数据库连接串 jdbc://user:pwd@host"));

        assertEquals(ErrorCode.SYS_ERROR.getCode(), body.getCode());
        assertEquals(ErrorCode.SYS_ERROR.getMessage(), body.getMessage());
        assertFalse(body.getMessage().contains("jdbc"), "兜底响应不得把内部细节泄漏给前端");
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
