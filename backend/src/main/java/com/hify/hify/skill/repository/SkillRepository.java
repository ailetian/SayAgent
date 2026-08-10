package com.hify.hify.skill.repository;

import com.hify.hify.skill.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 技能仓库（M8/T4，§3.2 自包含 repository）。
 *
 * <p>大白话：skill 表的增删查改入口。软删除由 {@code @SQLRestriction} 自动过滤（见 §6.1）。
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {

    /** 按名字查（内置同步用）；软删除自动过滤。 */
    Optional<Skill> findByName(String name);

    /** 列出启用中的技能（禁用/已删不返回）。 */
    List<Skill> findByEnabledTrue();
}
