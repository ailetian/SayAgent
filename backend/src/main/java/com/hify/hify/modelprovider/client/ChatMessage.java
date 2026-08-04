package com.hify.hify.modelprovider.client;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 单条对话消息（§3.5 强类型 DTO）。
 *
 * <p>大白话：一轮对话（谁说的、说了啥）。各 Client 负责把它翻译成厂商认识的 role 文案
 * （如 Gemini 把 assistant 翻成 model）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 角色：user / assistant / system（由各 Client 映射到厂商格式）。 */
    private String role;

    /** 消息正文。 */
    private String content;
}
