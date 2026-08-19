package com.sayagent.skill.web;

import com.sayagent.common.Result;
import com.sayagent.skill.entity.Skill;
import com.sayagent.skill.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能管理接口「前台柜员」（M8/T4，提示词式，§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：/api/skills 一组入口，管技能的增删查改。响应统一包 {@link Result}。
 * 技能是「提示词块」，含 {@code promptText}/{@code description}（admin 可见、无 secret 可脱敏），
 * 写操作权限在服务层 {@link SkillService} 再核（§7.11）。
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Result<List<SkillResponse>> listSkills() {
        return Result.ok(skillService.listSkills().stream().map(SkillResponse::from).toList());
    }

    @GetMapping("/{id}")
    public Result<SkillResponse> getSkill(@PathVariable Long id) {
        return Result.ok(SkillResponse.from(skillService.getSkill(id)));
    }

    @PostMapping
    public Result<SkillResponse> createSkill(@RequestBody SkillCreateRequest req) {
        return Result.ok(SkillResponse.from(
                skillService.createSkill(req.name(), req.description(), req.promptText())));
    }

    @PutMapping("/{id}")
    public Result<SkillResponse> updateSkill(@PathVariable Long id, @RequestBody SkillUpdateRequest req) {
        return Result.ok(SkillResponse.from(skillService.updateSkill(
                id, req.name(), req.description(), req.promptText(), req.enabled())));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return Result.ok();
    }

    // === 请求 / 响应契约（嵌套记录，避免额外文件；§3.5 强类型） ===

    /** 创建技能请求。 */
    public record SkillCreateRequest(String name, String description, String promptText) {
    }

    /** 更新技能请求（字段均可空，部分更新）。 */
    public record SkillUpdateRequest(String name, String description, String promptText, Boolean enabled) {
    }

    /**
     * 技能对外视图：含 {@code promptText}/{@code description}（admin 可见、无 secret 可脱敏）。
     */
    public record SkillResponse(Long id, String name, String description, String promptText,
                                Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {

        /** 把内部实体翻译成对外 VO。 */
        public static SkillResponse from(Skill s) {
            return new SkillResponse(
                    s.getId(),
                    s.getName(),
                    s.getDescription(),
                    s.getPromptText(),
                    s.getEnabled(),
                    s.getCreatedAt(),
                    s.getUpdatedAt());
        }
    }
}
