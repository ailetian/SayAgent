package com.sayagent.conversation.service;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.conversation.dto.FeedbackAdminView;
import com.sayagent.conversation.entity.Conversation;
import com.sayagent.conversation.entity.Message;
import com.sayagent.conversation.entity.MessageFeedback;
import com.sayagent.conversation.repository.ConversationRepository;
import com.sayagent.conversation.repository.MessageFeedbackRepository;
import com.sayagent.conversation.repository.MessageRepository;
import com.sayagent.user.UserService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 回答反馈服务（K0808 T8）。
 *
 * <p>大白话：用户对某条 AI 回答「赞/踩」后，这里负责落库（覆盖写，重复点=更新）；
 * 也负责管理员查看「被踩排行榜」、以及当前用户拉取自己的反馈（前端回显用）。
 *
 * <p>只做业务判断与编排，数据访问全走 Repository（§3.2 分层职责边界）。鉴权在服务层兜底
 * （控制器只挡未登录 401，角色校验在 {@link #adminView} 内做 FORBIDDEN，坑位 31）。
 */
@Service
public class MessageFeedbackService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final MessageFeedbackRepository feedbackRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final UserService userService;

    public MessageFeedbackService(MessageFeedbackRepository feedbackRepository,
                                  MessageRepository messageRepository,
                                  ConversationRepository conversationRepository,
                                  UserService userService) {
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.userService = userService;
    }

    /**
     * 提交/取消反馈（upsert 语义，按 (message_id, user_id)）。
     * rating 为 null 或空 = 取消评价（删除覆盖行）；其余情况覆盖写。
     *
     * @param username  当前登录名（来自 JWT principal，禁止前端传 user_id，§7.11）
     * @param messageId 被评价的消息 id
     * @param rating    评价类型（THUMBS_UP/THUMBS_DOWN），空=取消
     * @param reason    踩的原因（仅 THUMBS_DOWN 时填，可空）
     */
    public void submit(String username, Long messageId, String rating, String reason) {
        Long userId = userService.resolveUserId(username);
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND));

        // rating 为空 → 取消评价。DB 中 rating NOT NULL（V28），无法存 null，故走删除覆盖行。
        if (!StringUtils.hasText(rating)) {
            feedbackRepository.deleteByMessageIdAndUserId(messageId, userId);
            return;
        }

        MessageFeedback.Rating parsed;
        try {
            parsed = MessageFeedback.Rating.valueOf(rating.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "rating 仅支持 THUMBS_UP / THUMBS_DOWN");
        }

        // agent_id 从消息所属会话取（便于按 Agent 聚合被踩）；kb_id 当前无消息级关联，留 null（T10/T11 可能补齐）。
        String agentId = resolveAgentId(message.getConversationId());
        feedbackRepository.upsert(messageId, userId, agentId, null, parsed.name(), reason);
    }

    /** 从消息所属会话解析 agent_id（会话无 agent 时返回 null）。 */
    private String resolveAgentId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        Optional<Conversation> conv = conversationRepository.findByConversationId(conversationId);
        return conv.map(Conversation::getAgentId).orElse(null);
    }

    /**
     * 管理员视角：被踩 TOP-N（按被踩次数降序）+ 原因分布。
     * 非 ADMIN 抛 FORBIDDEN（角色校验在服务层兜底，坑位 31）。
     *
     * @param auth    当前认证（含角色），由控制器从 SecurityContext 传入
     * @param kbId    按知识库筛选（可空）
     * @param agentId 按 Agent 筛选（可空）
     * @param limit   TOP-N 上限（≤0 时回退 20）
     */
    public FeedbackAdminView adminView(Authentication auth, Long kbId, String agentId, int limit) {
        if (auth == null || auth.getAuthorities() == null
                || auth.getAuthorities().stream().noneMatch(a -> ROLE_ADMIN.equals(a.getAuthority()))) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        int n = (limit <= 0) ? 20 : limit;
        List<Object[]> top = feedbackRepository.topThumbsDownMessages(kbId, agentId, n);
        List<Object[]> reasons = feedbackRepository.reasonDistribution(kbId, agentId, n);
        return FeedbackAdminView.from(top, reasons);
    }

    /**
     * 当前用户对自己若干消息的反馈（前端回显已踩/赞状态，T9 支持）。
     *
     * @param username   当前登录名
     * @param messageIds 要查的消息 id 列表
     * @return messageId → rating 字符串 的映射（未评价的消息不出现）
     */
    public Map<Long, String> myRatings(String username, List<Long> messageIds) {
        Long userId = userService.resolveUserId(username);
        Map<Long, String> map = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return map;
        }
        List<MessageFeedback> rows = feedbackRepository.findByUserIdAndMessageIdIn(userId, messageIds);
        for (MessageFeedback f : rows) {
            map.put(f.getMessageId(), f.getRating().name());
        }
        return map;
    }
}
