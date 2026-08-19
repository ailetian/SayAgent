package com.sayagent.knowledge.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.knowledge.entity.KnowledgeBase;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

/**
 * 知识库仓储（M5 T1）。无额外查询方法，复用 {@link BaseRepository} 的通用 CRUD（含软删语义）。
 */
public interface KnowledgeBaseRepository extends BaseRepository<KnowledgeBase> {

    /**
     * keyset 游标分页（K8 §6.4）：取 id 严格小于 {@code lastId} 的库，按 id 倒序。
     */
    List<KnowledgeBase> findByIdLessThanOrderByIdDesc(Long lastId, Pageable pageable);

    /**
     * T6 列表可见性过滤：取指定可见性的全部库 id（索引 idx_visibility 命中）。
     * 用于「PUBLIC ∪ 显式授权」并集中的 PUBLIC 一侧。
     */
    @Query("SELECT kb.id FROM KnowledgeBase kb WHERE kb.visibility = ?1")
    List<Long> findIdsByVisibility(String visibility);

    /**
     * T6 列表可见性过滤（首页）：按可见 id 集合倒序取第一页（§6.4 keyset）。
     */
    List<KnowledgeBase> findByIdInOrderByIdDesc(Set<Long> ids, Pageable pageable);

    /**
     * T6 列表可见性过滤（翻页）：id 在可见集合内且严格小于 {@code lastId}，按 id 倒序（§6.4 keyset）。
     */
    List<KnowledgeBase> findByIdInAndIdLessThanOrderByIdDesc(Set<Long> ids, Long lastId, Pageable pageable);

    /** 直接更新 rag_config JSON 列（绕过 JPA dirty checking）。 */
    @Modifying
    @Query(value = "UPDATE knowledge_base SET rag_config = ?1 WHERE id = ?2", nativeQuery = true)
    int updateRagConfig(String ragConfig, Long id);
}
