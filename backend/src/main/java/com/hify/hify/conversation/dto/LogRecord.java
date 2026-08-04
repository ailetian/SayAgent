package com.hify.hify.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 对话流水日志的入参载体（M6 T4）。
 *
 * <p>大白话：调用方（T3 ConversationService）把"谁 / 用哪个 Agent / 哪个会话 / 问了啥 /
 * 花多少 token / 是否降级"装进这个简单对象，丢给 {@code ConversationLogAsyncWriter.log(...)} 后立刻返回，
 * 真正的落库在后台线程完成。
 *
 * <p>仅承载非敏感流水字段（§7.11 规则37：绝不含 API key / token 密钥 / 密码）。这里的 token 仅指
 * 用量计数（in_tok / out_tok），与密钥无关；question 是用户问题文本，属日志正常记录范畴。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogRecord {

    /** 谁问的（→user.id）。 */
    private Long userId;

    /** 用的哪个 Agent（业务键，VARCHAR(50)，对应 conversation.agent_id）。 */
    private String agentId;

    /** 所属会话（业务键，VARCHAR(50)，对应 conversation.conversation_id）。 */
    private String conversationId;

    /** 用户问题（MEDIUMTEXT，不索引）。 */
    private String question;

    /** 输入 token 用量计数（非密钥）。 */
    private Integer inTok = 0;

    /** 输出 token 用量计数（非密钥）。 */
    private Integer outTok = 0;

    /** 实际命中厂商。 */
    private String provider;

    /** 实际模型。 */
    private String model;

    /** 是否走降级。 */
    private Boolean fallback = false;
}
