package com.hify.hify.skill;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.skill.entity.Skill;
import com.hify.hify.skill.repository.SkillRepository;
import com.hify.hify.skill.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * SkillService 单测（M8/T4，提示词式，§7.10 规则34 命名 / §7.10 规则35 不连真库，纯 Mockito）。
 *
 * <p>验收点：composePersona 把挂载的技能提示词拼进人设；禁用/删后不可见；挂载校验抛 SKILL_NOT_FOUND。
 */
@ExtendWith(MockitoExtension.class)
public class SkillServiceTest {

    @Mock
    private SkillRepository repository;

    private SkillService skillService;

    @BeforeEach
    void setUp() {
        skillService = new SkillService(repository);
    }

    /** 造一个技能（指定启用状态）。 */
    private Skill skill(Long id, String name, String prompt, boolean enabled) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        s.setPromptText(prompt);
        s.setEnabled(enabled);
        return s;
    }

    @Test
    void testComposePersona_mountsPrompt() {
        when(repository.findById(1L)).thenReturn(Optional.of(skill(1L, "jargon", "用大白话解释术语", true)));
        String p = skillService.composePersona("你是客服助手", List.of(1L));
        assertTrue(p.contains("你是客服助手"), "人设应保留");
        assertTrue(p.contains("用大白话解释术语"), "挂载的技能提示词应拼进人设");
        assertTrue(p.contains("【已挂载的技能指令】"), "应有技能指令分隔段");
    }

    @Test
    void testComposePersona_noSkillReturnsPersona() {
        String p = skillService.composePersona("你是客服助手", null);
        assertEquals("你是客服助手", p, "无技能时应原样返回人设");
    }

    @Test
    void testComposePersona_disabled_invisible() {
        when(repository.findById(1L)).thenReturn(Optional.of(skill(1L, "jargon", "用大白话解释术语", false)));
        String p = skillService.composePersona("你是客服助手", List.of(1L));
        assertTrue(p.contains("你是客服助手"));
        assertFalse(p.contains("用大白话解释术语"), "禁用技能不应拼入人设");
    }

    @Test
    void testComposePersona_deleted_invisible() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        String p = skillService.composePersona("你是客服助手", List.of(1L));
        assertFalse(p.contains("用大白话解释术语"), "已删技能不应拼入人设");
    }

    @Test
    void testAssertExists_missing_throwsSkillNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> skillService.assertExists(99L));
        assertEquals(ErrorCode.SKILL_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testAssertExists_disabled_throwsSkillNotFound() {
        when(repository.findById(1L)).thenReturn(Optional.of(skill(1L, "x", "p", false)));
        BizException ex = assertThrows(BizException.class, () -> skillService.assertExists(1L));
        assertEquals(ErrorCode.SKILL_NOT_FOUND, ex.getErrorCode());
    }
}
