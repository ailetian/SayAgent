package com.sayagent.agent.repository;

import com.sayagent.agent.entity.Agent;
import com.sayagent.common.base.BaseRepository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 仓储（M4/T1，§3.2 后端包结构）。
 *
 * <p>大白话：把对 agent 表的增删改查交给 Spring Data，本接口只声明「按条件找」的派生查询。
 * 继承 {@link BaseRepository} 白捡 JPA 通用能力；软删除过滤由实体上的 {@code @SQLRestriction} 自动生效。
 */
public interface AgentRepository extends BaseRepository<Agent> {

    /** 列出某模型厂商下的全部 Agent（含已停用，但已软删的被 @SQLRestriction 过滤）。 */
    List<Agent> findByModelProviderId(Long modelProviderId);

    /** 列出所有启用中的 Agent，按排序权重升序（供对话入口/M6 路由取可用 Agent）。 */
    List<Agent> findAllByEnabledTrueOrderBySortOrderAsc();

    /** 取唯一默认 Agent（全表至多一条 is_default_agent=1 且未软删）。 */
    Optional<Agent> findByDefaultAgentTrue();

    /** 同厂商下按名称查重（创建/改名时防重名）。 */
    Optional<Agent> findByModelProviderIdAndName(Long modelProviderId, String name);

    /**
     * T6 列表可见性过滤：取指定可见性的全部 Agent id（索引 idx_visibility 命中）。
     */
    @Query("SELECT a.id FROM Agent a WHERE a.visibility = ?1")
    List<Long> findIdsByVisibility(String visibility);

    /**
     * T6 列表可见性过滤：按可见 id 集合倒序取（§6.4 keyset 的 HOME 端，Agent 列表本身无须翻页）。
     */
    List<Agent> findByIdInOrderByIdDesc(Set<Long> ids);
}
