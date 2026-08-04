package com.hify.hify.knowledge.entity;

/**
 * 知识库访问授权目标类型（RBAC，M5 整改扩展）。
 * <ul>
 *   <li>{@code ROLE}：按角色授权（target_id 存角色名，如 USER/ADMIN，未来可扩展 LEADER 等）</li>
 *   <li>{@code USER}：按具体人授权（target_id 存登录名 username）</li>
 * </ul>
 */
public enum KbAccessTargetType {
    ROLE,
    USER
}
