package com.hify.hify.knowledge.service;

import com.hify.hify.modelprovider.client.ChatMessage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewriting（R4，需求 §5.5）——轻量版（首版）。
 *
 * <p>大白话：用户口语化、带指代的提问（"那婚假呢？""它怎么算？"）直接丢给检索/大模型，
 * 经常搜不准、答偏。本类在检索前把问题"翻译"成更完整、更书面的查询：
 * <ul>
 *   <li><b>口语清理</b>：去掉开头寒暄（"你好/请问/帮我看下"）和结尾客套（"谢谢/呢/嘛"），保留原问题所有约束只改表达（E8）。</li>
 *   <li><b>多轮指代消解</b>：从最近对话历史里找出被指代的实体，把"那婚假呢"补成"婚假"、
 *       把"它呢"补成上一条用户问题里的核心名词（如"年假"）。</li>
 * </ul>
 *
 * <p>为什么是<b>规则版</b>而非调大模型：首版要求轻量、确定、零额外出网（不抢占 K5 唯一的 LLM 调用额度，
 * 也不引入额外失败面）；规则可单测、可解释。后续如需更强语义改写，可在此接口后替换实现，
 * 不影响 {@link RagQueryService} 的调用方式（§3.2 面向接口）。
 *
 * <p>注意：历史的"存最近 6~10 轮"由上层（K8 接口层）负责截取后传入，本类只消费传入的 {@code history}。
 */
@Component
public class QueryRewriter {

    /** 指代消解：从最近一条用户消息里抠出核心名词时的中文疑问词（先剔除再取前部名词）。 */
    private static final Pattern QUESTION_WORDS = Pattern.compile(
            "怎么|如何|怎样|什么样|什么|多少|为什么|为何|哪|几|吗|呢|啊|呀|嘛|？|\\?|。|\\.|，|,");

    /** 取字符串开头的连续「中文/字母/数字/下划线/间隔号」片段作为候选名词（最长 8）。 */
    private static final Pattern LEADING_NOUN = Pattern.compile("^([\\u4e00-\\u9fa5A-Za-z0-9_·]{1,8})");

    /** 名词后常见的尾缀动词/语气字（抠完疑问词后再去掉，避免「年假请」残留「请」）。 */
    private static final Pattern TRAILING_VERB = Pattern.compile("[请问说查看了吧哦嘛呀呗]+$");

    /** 那X呢 / 这X呢：X 为显式名词（非量词开头），直接抽取 X 作为焦点。 */
    private static final Pattern DEMONSTRATIVE =
            Pattern.compile("^(那|这)([\\u4e00-\\u9fa5A-Za-z0-9_·]{1,12}?)(呢|吗|呀)?[？?\\s]*$");

    /** 纯指代问句：它/这个/那个/前者/后者/上一条 等，需要回历史找实体替换。 */
    private static final Pattern BARE_ANAPHORA =
            Pattern.compile("^(那|这|请问|我想问|帮我看下)?"
                    + "(它|它们|这个|那个|前者|后者|上面|上一条|这条|那条)"
                    + "(.{0,6}?)(呢|吗|呀)?[？?\\s]*$");

    /** 开头寒暄（一次性匹配，顺序无关）。 */
    private static final Pattern LEADING_FILLER =
            Pattern.compile("^(你好|您好|在吗|hi|hello|请问|我想问|我想知道|我想了解|帮我看下|帮我看看|麻烦你|劳驾)[，,：:：\\s]*");

    /** 结尾客套/语气词。 */
    private static final Pattern TRAILING_FILLER =
            Pattern.compile("[，,。.？?\\s]*(谢谢|多谢|辛苦了|麻烦了|呢|嘛|啊|呀|哦|呗)[？?\\s]*$");

    /**
     * 改写查询。
     *
     * @param query   用户原始提问（非空）
     * @param history 最近对话历史（角色 user/assistant/system），可为空/null（无历史则只做口语清理）
     * @return 改写后的查询；若无任何可改写点，原样返回（不会返回 null）
     */
    public String rewrite(String query, List<ChatMessage> history) {
        if (query == null || query.isBlank()) {
            return query == null ? "" : query;
        }
        String cleaned = query.strip();
        if (cleaned.isEmpty()) {
            return cleaned;
        }

        // 1) 那X呢 / 这X呢：显式名词型指代，直接抽 X（如「那婚假呢」→「婚假」）
        Matcher dm = DEMONSTRATIVE.matcher(cleaned);
        if (dm.matches()) {
            String focus = dm.group(2);
            if (focus != null && !focus.isEmpty() && !isPureClassifier(focus.charAt(0))) {
                return focus;
            }
        }

        // 2) 纯指代问句（它/这个/那个…）：从最近一条用户消息取核心名词替换
        Matcher ba = BARE_ANAPHORA.matcher(cleaned);
        if (ba.matches()) {
            String noun = lastUserNoun(history);
            if (noun != null && !noun.isEmpty()) {
                return noun;
            }
        }

        // 3) 仅做口语清理（去掉寒暄/客套，保留原约束）
        return stripFiller(cleaned);
    }

    /**
     * 口语清理：循环去头部寒暄，再去尾部客套词与标点。
     * 包级可见，供同包 {@code QueryIntentClassifier} 复用（K0808 T2，避免重复实现 cleaning 逻辑）。
     */
    String stripFiller(String text) {
        String t = text;
        String prev;
        do {
            prev = t;
            t = LEADING_FILLER.matcher(t).replaceFirst("");
        } while (!t.equals(prev));
        t = TRAILING_FILLER.matcher(t).replaceFirst("");
        t = t.replaceAll("[？?。.！!]+$", "");
        return t.strip();
    }

    /** 取最近一条「用户」消息里的核心名词（供纯指代替换）。 */
    private String lastUserNoun(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && "user".equalsIgnoreCase(m.getRole()) && m.getContent() != null) {
                return extractNoun(m.getContent());
            }
        }
        return null;
    }

    /** 从一句话里抠出核心名词：先剔疑问词，再去尾缀动词，取开头连续名词片段。 */
    private String extractNoun(String text) {
        String t = QUESTION_WORDS.matcher(text).replaceAll("");
        t = TRAILING_VERB.matcher(t).replaceAll("");
        t = t.strip();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = LEADING_NOUN.matcher(t);
        return m.find() ? m.group(1) : t;
    }

    /** 是否量词开头（那/这 后若是「个/种/些/位/条/本…」说明没给显式名词，需走历史替换）。 */
    private boolean isPureClassifier(char c) {
        return c == '个' || c == '种' || c == '些' || c == '位' || c == '条' || c == '本'
                || c == '件' || c == '名' || c == '项' || c == '只' || c == '台';
    }
}
