package com.sayagent.knowledge.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.knowledge.entity.AgentKbLink;

import java.util.List;
import java.util.Optional;

/**
 * Agent ↔ KB 挂载关系仓储（K1/K7）。
 *
 * <p>软删除由实体 {@code @SQLRestriction("deleted = 0")} 统一过滤，以下派生查询默认只看未删记录，
 * 因此不需要再写 {@code AndDeletedFalse}（避免 {@code deleted = 0 and deleted = false} 的布尔绑定歧义）。
 */
public interface AgentKbLinkRepository extends BaseRepository<AgentKbLink> {

    /** 某 Agent 的全部有效挂载（已按软删过滤）。 */
    List<AgentKbLink> findByAgentId(Long agentId);

    /** 某 Agent 是否已挂载某库（已按软删过滤）。 */
    boolean existsByAgentIdAndKbId(Long agentId, Long kbId);

    /** 取某 Agent→某库的有效挂载记录（卸载前校验归属，已按软删过滤）。 */
    Optional<AgentKbLink> findByAgentIdAndKbId(Long agentId, Long kbId);

    /** 取某库的全部有效挂载记录（删除知识库前清挂载关系用，已按软删过滤）。 */
    List<AgentKbLink> findByKbId(Long kbId);
}
