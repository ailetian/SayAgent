package com.hify.hify.skill.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.skill.entity.Skill;
import com.hify.hify.skill.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能业务（M8/T4，提示词式，§3.2 自包含 service / §3.5 强类型 / §7.11 服务层权限）。
 *
 * <p>大白话：管理 skill 表的增删查改；并提供 {@link #composePersona}——把 Agent 挂载的技能提示词
 * 拼进该 Agent 的人设（配置时静态组合，v1 非运行时模型自选）。技能是「提示词块」，不是工具，
 * 因此<b>不</b>实现 {@code common.tool.Tool}、<b>不</b>进函数调用工具列表（那是 MCP/内置工具的活）。
 *
 * <p>解耦纪律（§3.2）：conversation / agent 模块只 {@code @Autowired SkillService}，<b>禁止</b> import
 * skill 内部 entity/repository；{@link #composePersona} 返回 {@code String}（拼好的提示词），
 * 不泄露 {@link Skill} 内部类。
 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);
    /** 拼装到人设后的分隔标识，便于模型区分「固定人设」与「挂载技能指令」。 */
    private static final String SKILL_SECTION_HEADER = "\n\n【已挂载的技能指令】\n";

    private final SkillRepository repository;

    /** 列出全部技能（软删除由 @SQLRestriction 过滤）。 */
    public List<Skill> listSkills() {
        return repository.findAll();
    }

    /** 查看单个技能；不存在抛 SKILL_NOT_FOUND。 */
    public Skill getSkill(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "id=" + id));
    }

    /** 新增技能（仅 ADMIN，§7.11）。 */
    public Skill createSkill(String name, String description, String promptText) {
        assertAdmin();
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "技能名称不能为空");
        }
        if (promptText == null || promptText.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提示词正文不能为空");
        }
        if (repository.findByName(name).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "技能名已存在: " + name);
        }
        Skill skill = new Skill();
        skill.setName(name);
        skill.setDescription(description);
        skill.setPromptText(promptText);
        skill.setEnabled(true);
        return repository.save(skill);
    }

    /** 更新技能（仅 ADMIN，§7.11）；仅更新非 null 字段。 */
    public Skill updateSkill(Long id, String name, String description, String promptText, Boolean enabled) {
        assertAdmin();
        Skill skill = getSkill(id);
        if (name != null) {
            skill.setName(name);
        }
        if (description != null) {
            skill.setDescription(description);
        }
        if (promptText != null) {
            skill.setPromptText(promptText);
        }
        if (enabled != null) {
            skill.setEnabled(enabled);
        }
        return repository.save(skill);
    }

    /** 删除技能（软删，仅 ADMIN，§7.11）。 */
    public void deleteSkill(Long id) {
        assertAdmin();
        Skill skill = getSkill(id);
        repository.delete(skill);
    }

    /**
     * 校验技能存在且启用（供 Agent 挂载校验，§3.2）。
     * 不存在 / 已禁用 / 已删 → 抛 SKILL_NOT_FOUND。
     */
    public void assertExists(Long id) {
        Skill skill = repository.findById(id).orElse(null);
        if (skill == null || !Boolean.TRUE.equals(skill.getEnabled())) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "id=" + id);
        }
    }

    /**
     * 把挂载的 skill 提示词拼进 Agent 人设（配置时静态组合，v1 非运行时自选）。
     * 顺序：人设 → 各挂载 skill 的 {@code promptText}（按 skillRefs 顺序）。
     * 禁用/已软删的 skill 自动跳过（对该 Agent 不可见，§4.5）。
     *
     * @param basePersona  Agent 人设/系统提示词原文（可能为 null/空）
     * @param skillRefs    挂载的技能 id 列表（可能 null / 空）
     * @return 拼好 skill 提示词的人设；若 skillRefs 为空则原样返回 basePersona
     */
    public String composePersona(String basePersona, List<Long> skillRefs) {
        if (skillRefs == null || skillRefs.isEmpty()) {
            return basePersona == null ? "" : basePersona;
        }
        StringBuilder sb = new StringBuilder();
        if (basePersona != null && !basePersona.isBlank()) {
            sb.append(basePersona);
        }
        List<String> blocks = new ArrayList<>();
        for (Long id : skillRefs) {
            Skill skill = repository.findById(id).orElse(null);
            if (skill == null || !Boolean.TRUE.equals(skill.getEnabled())) {
                continue; // 禁用/删除 → 不可见
            }
            blocks.add(skill.getPromptText());
        }
        if (!blocks.isEmpty()) {
            sb.append(SKILL_SECTION_HEADER);
            sb.append(String.join("\n\n", blocks));
        }
        return sb.toString();
    }

    /** 服务层权限再核（§7.11）：当前登录用户须为 ROLE_ADMIN，否则 FORBIDDEN。 */
    private void assertAdmin() {
        if (!isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可管理技能");
        }
    }

    /** 当前登录用户是否管理员（ROLE_ADMIN）。 */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(au -> "ROLE_ADMIN".equals(au.getAuthority()));
    }
}
