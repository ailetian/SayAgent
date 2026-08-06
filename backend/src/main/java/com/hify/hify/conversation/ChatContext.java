package com.hify.hify.conversation;

import com.hify.hify.modelprovider.client.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话编排上下文（M6 T3 值对象）。
 *
 * <p>大白话：把"这次对话要用的所有素材"装进一个容器——Agent 信息、用户问题、历史消息、
 * 召回的知识、最终发给 LLM 的 messages 列表。编排各步（取 Agent → 召回 → 组装 → 落库 → 写日志）
 * 都围着它转，避免在方法间传一堆散参数（用强类型上下文对象聚合编排参数，避免散参数签名膨胀）。
 *
 * <p>构造后用 {@link #withRetrievedKnowledge(String)} / {@link #withMessages(List)} 追加召回结果与最终消息列表，
 * 因为这两步依赖前面已解析好的 Agent 信息；其余字段在 builder 阶段一次性定好。
 */
public class ChatContext {

    private final Long userId;
    private final String conversationId;
    private final String agentIdStr;
    private final Long agentDbId;
    private final String agentName;
    private final String systemPrompt;
    private final Long providerRef;
    private final String providerType;
    private final String model;
    private final List<Long> knowledgeRefs;
    private final List<Long> toolRefs;
    private final String question;
    private final List<ChatMessage> history;

    private String retrievedKnowledge = "";
    private List<ChatMessage> messages = List.of();
    /** 调用轨迹（KB 检索 / MCP 工具调用明细），编排过程中累积，最终序列化进 message.trace_json。 */
    private List<CallTrace> trace = new ArrayList<>();

    private ChatContext(Builder b) {
        this.userId = b.userId;
        this.conversationId = b.conversationId;
        this.agentIdStr = b.agentIdStr;
        this.agentDbId = b.agentDbId;
        this.agentName = b.agentName;
        this.systemPrompt = b.systemPrompt;
        this.providerRef = b.providerRef;
        this.providerType = b.providerType;
        this.model = b.model;
        this.knowledgeRefs = b.knowledgeRefs;
        this.toolRefs = b.toolRefs;
        this.question = b.question;
        this.history = b.history;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getUserId() {
        return userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentIdStr() {
        return agentIdStr;
    }

    public Long getAgentDbId() {
        return agentDbId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Long getProviderRef() {
        return providerRef;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getModel() {
        return model;
    }

    public List<Long> getKnowledgeRefs() {
        return knowledgeRefs;
    }

    public List<Long> getToolRefs() {
        return toolRefs;
    }

    public String getQuestion() {
        return question;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public String getRetrievedKnowledge() {
        return retrievedKnowledge;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** 调用轨迹列表（编排过程累积，最终落库 message.trace_json）。 */
    public List<CallTrace> getTrace() {
        return trace;
    }

    /** 追加召回到的知识文本（可能为""表示无知识）。 */
    public ChatContext withRetrievedKnowledge(String knowledge) {
        this.retrievedKnowledge = knowledge == null ? "" : knowledge;
        return this;
    }

    /** 追加最终发给 LLM 的 messages 列表。 */
    public ChatContext withMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? List.of() : messages;
        return this;
    }

    public static class Builder {
        private Long userId;
        private String conversationId;
        private String agentIdStr;
        private Long agentDbId;
        private String agentName;
        private String systemPrompt;
        private Long providerRef;
        private String providerType;
        private String model;
        private List<Long> knowledgeRefs = List.of();
        private List<Long> toolRefs = List.of();
        private String question;
        private List<ChatMessage> history = List.of();

        public Builder userId(Long v) {
            this.userId = v;
            return this;
        }

        public Builder conversationId(String v) {
            this.conversationId = v;
            return this;
        }

        public Builder agentIdStr(String v) {
            this.agentIdStr = v;
            return this;
        }

        public Builder agentDbId(Long v) {
            this.agentDbId = v;
            return this;
        }

        public Builder agentName(String v) {
            this.agentName = v;
            return this;
        }

        public Builder systemPrompt(String v) {
            this.systemPrompt = v;
            return this;
        }

        public Builder providerRef(Long v) {
            this.providerRef = v;
            return this;
        }

        public Builder providerType(String v) {
            this.providerType = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder knowledgeRefs(List<Long> v) {
            this.knowledgeRefs = v;
            return this;
        }

        public Builder toolRefs(List<Long> v) {
            this.toolRefs = v;
            return this;
        }

        public Builder question(String v) {
            this.question = v;
            return this;
        }

        public Builder history(List<ChatMessage> v) {
            this.history = v;
            return this;
        }

        public ChatContext build() {
            return new ChatContext(this);
        }
    }

    /**
     * 调用轨迹明细（M6 T3）：一次对话里「知识库检索命中」或「MCP 工具调用」的一条记录。
     *
     * <p>大白话：把"这次对话 AI 背后调了啥"原样记下来——哪篇文档命中、相似度多少、调了哪个 MCP 工具、
     * 入参出参是什么。序列化为 {@code message.trace_json}，前端对话详情页可展开回看。
     * 对话日志铁律：KB/MCP 调用记录一律不得删除、必须持久化，即便模型最终没引用也要留痕。
     */
    public record CallTrace(
            /** retrieval=知识库检索 / tool=MCP 工具调用。 */
            String kind,
            /** 标题文案（如「知识库命中：文档 doc#1 片段#2（相似度 0.812）」）。 */
            String label,
            /** done / running / error。 */
            String status,
            /** retrieval：命中片段所属文档业务 id。 */
            String docId,
            /** retrieval：余弦相似度 [-1,1]，越大越相关。 */
            Double score,
            /** tool：工具名。 */
            String toolName,
            /** tool：入参 JSON。 */
            String args,
            /** retrieval：命中片段摘要；tool：工具返回摘要。 */
            String result,
            /** tool：是否成功（false=降级/不可用）。 */
            Boolean success
    ) {
    }
}
