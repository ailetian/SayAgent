package com.hify.hify.conversation.dto;

import java.util.List;

/**
 * 管理员反馈视图（K0808 T8）。
 *
 * <p>大白话：给管理员看的"哪些回答最招踩 + 大家为什么踩"两份榜单。
 * {@code top} 是按被踩次数降序的回答清单，{@code reasons} 是原因分布计数。
 */
public record FeedbackAdminView(
        /** 被踩最多的消息 TOP-N（[messageId, count] 倒序）。 */
        List<ThumbsDownItem> top,
        /** 踩的原因分布 TOP-N（[reason, count] 倒序）。 */
        List<ReasonCount> reasons
) {

    /** 单条被踩计数（哪条消息、被踩几次）。 */
    public record ThumbsDownItem(Long messageId, long count) {}

    /** 原因分布计数（哪个原因、出现几次）。 */
    public record ReasonCount(String reason, long count) {}

    /**
     * 把原生聚合结果（每行 {@code Object[]}）转成强类型视图。
     * MySQL {@code BIGINT UNSIGNED} 经 Hibernate 返回为 {@code BigInteger}，统一按 {@code Number} 取 long。
     *
     * @param top     原生 [message_id, cnt]
     * @param reasons 原生 [reason, cnt]
     */
    public static FeedbackAdminView from(List<Object[]> top, List<Object[]> reasons) {
        List<ThumbsDownItem> topList = top.stream()
                .map(r -> new ThumbsDownItem(((Number) r[0]).longValue(), ((Number) r[1]).longValue()))
                .toList();
        List<ReasonCount> reasonList = reasons.stream()
                .map(r -> new ReasonCount((String) r[0], ((Number) r[1]).longValue()))
                .toList();
        return new FeedbackAdminView(topList, reasonList);
    }
}
