package com.sayagent.knowledge.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 挂载请求（K7）：把某个知识库挂到某个 Agent。
 *
 * <p>大白话：调用方只传「要挂哪个知识库」，Agent 由路径 {@code {agentId}} 决定；
 * 操作人取当前登录用户，权限由 {@code MountService} 校验（仅 Agent 创建者/admin）。
 */
public record MountRequest(

        @NotNull(message = "kbId 不能为空")
        Long kbId) {
}
