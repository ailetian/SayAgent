package com.sayagent.conversation.dto;

import com.sayagent.conversation.web.ChatMessageVO;

import java.util.List;

/**
 * 历史消息 keyset 分页返回（M6 T2，§6.4）。
 *
 * <p>大白话：一次只给一页（最新 20 条），告诉前端"还有没有更多"(hasMore) 和"下一页从哪条开始翻"(nextCursor)；
 * 不返回总数（流式应用无总数需求，且总数在大表上慢，§6.4）。
 */
public record ChatHistoryPage(
        /** 本页消息（旧→新时间线；keyset 命中 {@code idx_conv_created}）。 */
        List<ChatMessageVO> items,
        /** 下一页游标：本页最旧一条的 id；没有下一页则为 null。 */
        Long nextCursor,
        /** 是否还有更多（本页取满一页即视为可能还有）。 */
        boolean hasMore
) {
}
