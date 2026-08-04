package com.hify.hify.knowledge.dto;

import com.hify.hify.knowledge.entity.KbAccessTargetType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 知识库访问授权请求（M5 整改扩展 RBAC）。
 *
 * <p>大白话：管理员填「按角色(ROLE)还是按人(USER)授权」+「角色名或登录名」，分配给某个知识库。
 */
public record KbAccessRequest(

        @NotNull(message = "targetType 不能为空(ROLE/USER)")
        KbAccessTargetType targetType,

        @NotBlank(message = "targetId 不能为空(角色名或登录名)")
        String targetId) {
}
