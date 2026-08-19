package com.sayagent.conversation.web;

import com.sayagent.common.Result;
import com.sayagent.conversation.dto.ChatHistoryPage;
import com.sayagent.conversation.dto.ChatRequest;
import com.sayagent.conversation.dto.ConversationPinRequest;
import com.sayagent.conversation.dto.ConversationRenameRequest;
import com.sayagent.conversation.service.ConversationService;
import com.sayagent.conversation.web.ConversationVO;

import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话控制层（M6 T1/T2）。
 *
 * <p>大白话：极薄——只做参数校验、把请求委托给 ConversationService、把结果装进统一盒子 {@link Result}；
 * 鉴权与归属校验在服务层（§7.11）。/api/chat/stream 返回 text/event-stream（SSE）。
 */
@RestController
@RequestMapping("/api/chat")
public class ConversationController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** SSE 流式对话（M6/T3 编排推流）。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest req) {
        return conversationService.stream(req);
    }

    /** 列出当前用户的会话（须本人）。 */
    @GetMapping
    public Result<List<ConversationVO>> listConversations() {
        return Result.ok(conversationService.listConversations(currentUser()));
    }

    /** 列出某会话的消息（keyset 翻页，须本人）。 */
    @GetMapping("/{conversationId}/messages")
    public Result<ChatHistoryPage> listMessages(@PathVariable String conversationId,
                                                @RequestParam(required = false) Long lastId) {
        return Result.ok(conversationService.listMessages(conversationId, currentUser(), lastId, DEFAULT_PAGE_SIZE));
    }

    /** 删除会话（软删，须本人）。 */
    @DeleteMapping("/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId, currentUser());
        return Result.ok();
    }

    /** 重命名会话（须本人）。 */
    @PutMapping("/{conversationId}")
    public Result<Void> renameConversation(@PathVariable String conversationId,
                                           @Valid @RequestBody ConversationRenameRequest req) {
        conversationService.renameConversation(conversationId, currentUser(), req.title());
        return Result.ok();
    }

    /** 置顶 / 取消置顶（须本人）。 */
    @PutMapping("/{conversationId}/pin")
    public Result<Void> pinConversation(@PathVariable String conversationId,
                                        @Valid @RequestBody ConversationPinRequest req) {
        conversationService.pinConversation(conversationId, currentUser(), req.pinned());
        return Result.ok();
    }

    /** 取当前登录用户名（principal 由鉴权过滤器塞入，类型为 String username）。 */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }
}
