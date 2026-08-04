package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.AgentKbLink;

/**
 * Agent ↔ KB 挂载关系仓储（K1/K7）。无额外查询方法，复用 {@link BaseRepository} 通用 CRUD（含软删语义）。
 */
public interface AgentKbLinkRepository extends BaseRepository<AgentKbLink> {
}
