package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.Document;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储（M5 T1/T3）。按知识库列文档、按业务 document_id 取单条（供上传回调与状态查询）。
 */
public interface DocumentRepository extends BaseRepository<Document> {

    /** 某知识库下的全部文档（默认已带软删过滤，§6.1）。 */
    List<Document> findByKbId(Long kbId);

    /** 按业务 document_id 取文档（上传/状态查询入口）。 */
    Optional<Document> findByDocumentId(String documentId);

    /**
     * 跨库软删两段式·第一步（K4 P1）：按"已挂载的知识库 id 列表"筛出有效的文档业务 id。
     *
     * <p>大白话：PG 的 {@code document_chunk} 没有 {@code deleted} 列（软删在 MySQL 的
     * {@code document}/{@code knowledge_base} 上），所以先在 MySQL 用 JPA 把"没被删、且属于挂载库"的
     * 文档业务 id（{@code Document.documentId}，对外暴露的 UUID）捞出来，再下推给 PG 做
     * {@code WHERE document_id IN (...)} 过滤。注意：这里返回的是<b>业务 id 字符串</b>
     * （与 {@code document_chunk.document_id} 列同口径，该列由
     * {@code DocumentChunkRepository.saveChunk} 写入业务 UUID），<b>绝不能返回 {@code Document.id}
     * （自增 Long 主键）</b>——否则下推到 PG 的 Long 主键与 chunk 表里的 UUID 字符串对不上，
     * 混合检索会一条都搜不出来。{@code Document}/{@code KnowledgeBase} 实体都带
     * {@code @SQLRestriction("deleted = 0")}，软删被自动过滤，无需手动写 {@code WHERE deleted=0}。
     *
     * @param kbIds 当前 Agent 已挂载的知识库 id（多库联合检索的隔离维度）
     * @return 未被软删、且属于挂载库的文档业务 id（document_id 字符串）列表；空则表示无可检索文档
     */
    @Query("SELECT d.documentId FROM Document d JOIN KnowledgeBase kb ON d.kbId = kb.id WHERE kb.id IN :kbIds")
    List<String> findIdsByKbIdIn(@Param("kbIds") List<Long> kbIds);
}
