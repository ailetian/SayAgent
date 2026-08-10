package com.hify.hify.knowledge.service;

import com.hify.hify.modelprovider.client.ChatMessage;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图网关分类器（K0808 T2 / 意图网关）。
 *
 * <p>大白话：在决定「要不要去知识库翻答案」之前，先把问题分个类——纯客套？问我是谁？
 * 没意义的灌水？想让我「干活」？还是真问题？本类只做<b>纯函数判定</b>：
 * 不调大模型、不查数据库、不依赖 controller/repository（§3.2 分层职责边界）。</p>
 *
 * <p>判定顺序（自上而下，首命中即返回）：
 * <ol>
 *   <li>空 / 空白 → {@link Intent#MEANINGLESS}</li>
 *   <li>身份词组命中 → {@link Intent#IDENTITY}</li>
 *   <li>动作词组命中 → {@link Intent#TOOL}（本期占位，仅识别不实现）</li>
 *   <li>无意义词组精确命中 → {@link Intent#MEANINGLESS}</li>
 *   <li>复用 {@link QueryRewriter#stripFiller} 清洗后为空（纯客套）→ {@link Intent#GREETING}</li>
 *   <li>其余（清洗后非空）→ {@link Intent#QUESTION}</li>
 * </ol>
 * </p>
 */
@Component
public class QueryIntentClassifier {

    /** 身份类词组：用户想了解「你是谁 / 你能干什么」（§7.2 词组表集中常量，禁止字面量散落）。 */
    private static final String[] IDENTITY_PHRASES = {
            "你是谁", "你叫什么", "你能做什么", "你有什么用"
    };

    /** 动作类词组：用户想让助手「干活」（发邮件 / 订会议室…）。本期仅占位识别，不实现。 */
    private static final String[] TOOL_PHRASES = {
            "发邮件", "发送邮件", "订会议室", "预订", "提醒我", "帮我订"
    };

    /** 无意义词组：灌水 / 语气词 / 含糊应答（精确匹配，避免「嗯，年假怎么请」被误杀）。 */
    private static final String[] MEANINGLESS_PHRASES = {
            "？？？", "嗯", "哈哈", "好的", "明白了"
    };

    /** 清洗工具（复用 QueryRewriter，避免重复实现寒暄/客套清理逻辑，保持单一来源）。 */
    private final QueryRewriter queryRewriter;

    public QueryIntentClassifier(QueryRewriter queryRewriter) {
        this.queryRewriter = queryRewriter;
    }

    /**
     * 分类用户意图（带历史重载，预留上下文感知扩展；本期判定为无状态，不消费 history）。
     *
     * @param query   原始问题（可空）
     * @param history 对话历史（可为空；本期未使用，预留接口）
     * @return 意图枚举，不会返回 null
     */
    public Intent classify(String query, List<ChatMessage> history) {
        if (query == null || query.isBlank()) {
            return Intent.MEANINGLESS;
        }
        String q = query.strip();
        if (containsAny(q, IDENTITY_PHRASES)) {
            return Intent.IDENTITY;
        }
        if (containsAny(q, TOOL_PHRASES)) {
            return Intent.TOOL;
        }
        if (equalsAny(q, MEANINGLESS_PHRASES)) {
            return Intent.MEANINGLESS;
        }
        // 复用 QueryRewriter 的口语清理：纯客套清洗后为空 → GREETING
        String cleaned = queryRewriter.stripFiller(q);
        if (cleaned.isEmpty()) {
            return Intent.GREETING;
        }
        // 清洗后只剩 1 字（如残 punctuation / 单字语气）视为无意义
        if (cleaned.length() <= 1) {
            return Intent.MEANINGLESS;
        }
        return Intent.QUESTION;
    }

    /** 便捷重载：无历史场景。 */
    public Intent classify(String query) {
        return classify(query, null);
    }

    /** q 是否包含词组表中的任一短语（用于身份/动作类，兼容「你是谁啊」这类带后缀）。 */
    private static boolean containsAny(String q, String[] phrases) {
        for (String p : phrases) {
            if (q.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** q 是否精确等于词组表中的任一短语（用于无意义类，避免「嗯，年假怎么请」误杀）。 */
    private static boolean equalsAny(String q, String[] phrases) {
        for (String p : phrases) {
            if (q.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 意图枚举：GREETING 客套 / IDENTITY 问身份 / MEANINGLESS 无意义 / TOOL 动作(占位) / QUESTION 真问题。 */
    public enum Intent {
        GREETING,
        IDENTITY,
        MEANINGLESS,
        TOOL,
        QUESTION
    }
}
