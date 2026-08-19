package com.sayagent.conversation.dto;

/**
 * 提交反馈请求（K0808 T8）。
 *
 * <p>大白话：前端发来"这条回答我踩了/赞了，原因是啥"，装在这两个字段里。
 * {@code rating} 可空：传 {@code null}/空 = 取消评价（后端删除覆盖行，见 V28 rating NOT NULL 约定）；
 * 传 {@code THUMBS_UP}/{@code THUMBS_DOWN} = 覆盖写。{@code reason} 仅在踩时填，赞时可空。
 */
public record FeedbackRequest(
        /** 评价类型：THUMBS_UP 赞 / THUMBS_DOWN 踩；为空表示取消。 */
        String rating,
        /** 踩的原因（可选，如「检索不准」）。 */
        String reason
) {
}
