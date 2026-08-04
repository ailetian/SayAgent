package com.hify.hify.mcp.dto;

import com.hify.hify.mcp.McpServer;

import java.time.LocalDateTime;

/**
 * MCP Server 对外视图（M7/T1，§3.5 响应契约）。
 *
 * <p>大白话：返回给前端的「通讯录名片」。{@code address} 是内部服务地址而非秘钥，
 * 按 §7.11 规则37 可正常返回（若日后增加秘钥字段须 {@code @JsonIgnore} 或专用 VO 屏蔽）。
 */
public record McpServerVO(
        Long id,
        String name,
        String address,
        String type,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 把内部实体翻译成对外 VO。 */
    public static McpServerVO from(McpServer s) {
        return new McpServerVO(
                s.getId(),
                s.getName(),
                s.getAddress(),
                s.getType(),
                s.getStatus(),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
