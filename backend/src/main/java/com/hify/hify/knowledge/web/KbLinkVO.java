package com.hify.hify.knowledge.web;

/**
 * 挂载关系视图（K8 {@code GET /{agentId}/kb-links}）。
 *
 * <p>大白话：把一个 Agent 挂载了哪些知识库列出来。目前维度只有知识库 id（挂载关系本身由
 * {@code agent_kb_link} 表记录，含 created_by，但响应里不暴露敏感字段，遵循 §7.11 规则37）。
 */
public record KbLinkVO(Long kbId) {
}
