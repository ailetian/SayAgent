package com.sayagent.conversation.web;

import com.sayagent.common.Result;
import com.sayagent.conversation.dto.FeedbackAdminView;
import com.sayagent.conversation.dto.FeedbackRequest;
import com.sayagent.conversation.service.MessageFeedbackService;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答反馈控制层（K0808 T8）。
 *
 * <p>大白话：极薄——只做参数接收、把请求委托给 {@link MessageFeedbackService}、把结果装进统一盒子 {@link Result}。
 * 鉴权（未登录 401）由安全过滤链拦；角色校验（管理员才能看排行榜）在服务层兜底（§3.2 / §3.5 / §7.3）。
 */
@RestController
@RequestMapping("/api/chat")
public class MessageFeedbackController {

    private final MessageFeedbackService feedbackService;

    public MessageFeedbackController(MessageFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** 用户对某条回答点踩/赞（可带原因）；rating 为空 = 取消。 */
    @PostMapping("/messages/{id}/feedback")
    public Result<Void> submit(@PathVariable("id") Long messageId,
                               @RequestBody FeedbackRequest req) {
        feedbackService.submit(currentUser(), messageId, req.rating(), req.reason());
        return Result.ok();
    }

    /** 管理员：被踩 TOP-N + 原因分布。 */
    @GetMapping("/feedback")
    public Result<FeedbackAdminView> adminView(@RequestParam(required = false) Long kbId,
                                               @RequestParam(required = false) String agentId,
                                               @RequestParam(defaultValue = "20") int limit) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return Result.ok(feedbackService.adminView(auth, kbId, agentId, limit));
    }

    /** 当前用户拉取自己若干消息的反馈（前端回显已踩/赞状态，T9 支持）。 */
    @GetMapping("/feedback/mine")
    public Result<Map<Long, String>> myRatings(@RequestParam("messageIds") List<Long> messageIds) {
        return Result.ok(feedbackService.myRatings(currentUser(), messageIds));
    }

    /** 取当前登录用户名（principal 由鉴权过滤器塞入，类型为 String username）。 */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }
}
