package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.KnowledgeBase;

import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 知识库仓储（M5 T1）。无额外查询方法，复用 {@link BaseRepository} 的通用 CRUD（含软删语义）。
 */
public interface KnowledgeBaseRepository extends BaseRepository<KnowledgeBase> {

    /**
     * keyset 游标分页（K8 §6.4）：取 id 严格小于 {@code lastId} 的库，按 id 倒序。
     *
     * <p>大白话：列表「上一页最后一个 id」作为下一页的起点，只往更小 id 翻，避免 offset 深翻页性能塌方。
     * 首页（{@code lastId == null}）交给调用方改用 {@code findAll(Pageable)}，这里只处理「有游标」的情况。
     *
     * @param lastId   上一页末 id（游标），非 null
     * @param pageable 分页（通常 {@code PageRequest.of(0, limit + 1)} 多取一条判断 hasMore）
     * @return 命中的知识库列表（倒序）
     */
    List<KnowledgeBase> findByIdLessThanOrderByIdDesc(Long lastId, Pageable pageable);
}
