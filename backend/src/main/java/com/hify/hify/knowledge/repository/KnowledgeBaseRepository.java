package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.KnowledgeBase;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 知识库仓储（M5 T1）。无额外查询方法，复用 {@link BaseRepository} 的通用 CRUD（含软删语义）。
 */
public interface KnowledgeBaseRepository extends BaseRepository<KnowledgeBase> {

    /**
     * keyset 游标分页（K8 §6.4）：取 id 严格小于 {@code lastId} 的库，按 id 倒序。
     */
    List<KnowledgeBase> findByIdLessThanOrderByIdDesc(Long lastId, Pageable pageable);

    /** 直接更新 rag_config JSON 列（绕过 JPA dirty checking）。 */
    @Modifying
    @Query(value = "UPDATE knowledge_base SET rag_config = ?1 WHERE id = ?2", nativeQuery = true)
    int updateRagConfig(String ragConfig, Long id);
}
