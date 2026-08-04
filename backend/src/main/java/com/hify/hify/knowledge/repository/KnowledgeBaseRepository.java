package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.KnowledgeBase;

/**
 * 知识库仓储（M5 T1）。无额外查询方法，复用 {@link BaseRepository} 的通用 CRUD（含软删语义）。
 */
public interface KnowledgeBaseRepository extends BaseRepository<KnowledgeBase> {
}
