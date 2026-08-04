package com.hify.hify.knowledge.web;

import com.hify.hify.knowledge.entity.KbAccessTargetType;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库访问授权视图对象（M5 整改扩展 RBAC，规则37：不直返实体）。
 */
@Getter
@AllArgsConstructor
public class KbAccessVO {
    private Long id;
    private Long kbId;
    private KbAccessTargetType targetType;
    private String targetId;
}
