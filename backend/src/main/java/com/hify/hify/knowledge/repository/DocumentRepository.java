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
     * checksum 去重（K6 R8）：同一知识库内、相同校验和、且已索引成功（READY）的文档。
     *
     * <p>大白话：同一个文件重复上传，如果库里已经有一份"处理成功"的，就直接复用那份、
     * 不浪费算力再索引一遍。只认 INDEXED（READY）的——FAILED/INDEXING 的不算，避免复用半个坏文件。
     *
     * @param kbId          知识库 id
     * @param checksum      文件校验和（sha256）
     * @param status        目标状态（去重只认 INDEXED）
     * @return 匹配的已就绪文档列表（通常 0 或 1 条）；空列表表示无重复
     */
    List<Document> findByKbIdAndChecksumAndStatus(Long kbId, String checksum, Document.DocumentStatus status);

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

    /** 某知识库文档总数（K8 体检·基础健康用）。 */
    long countByKbId(Long kbId);

    /**
     * keyset 游标分页·首页（K11 / K9 缺口① §6.4）：某知识库下按 id 倒序取第一页。
     *
     * <p>大白话：文档列表必须<b>锁死在当前知识库</b>——早期实现用了不带 kbId 的
     * {@code findAll} / {@code findByIdLessThan}，会把别的库的文档一起列出来（串库）。
     * 列表接口虽有 {@code KbAccessGuard.requireAccessible(kbId)} 把住「能不能看这个库」，
     * 但过不了「只看这个库」的隔离，因此查询本身必须带 kbId 条件。
     *
     * @param kbId     知识库 id（隔离维度，必填）
     * @param pageable 分页（通常 {@code PageRequest.of(0, limit + 1)} 多取一条判断 hasMore）
     * @return 该库文档列表（按 id 倒序）
     */
    List<Document> findByKbIdOrderByIdDesc(Long kbId, org.springframework.data.domain.Pageable pageable);

    /**
     * keyset 游标分页·翻页（K11 / K9 缺口① §6.4）：某知识库下取 id 严格小于 {@code lastId} 的文档，按 id 倒序。
     *
     * @param kbId     知识库 id（隔离维度，必填）
     * @param lastId   上一页末 id（游标），非 null
     * @param pageable 分页（通常 {@code PageRequest.of(0, limit + 1)} 多取一条判断 hasMore）
     * @return 命中的文档列表（倒序）
     */
    List<Document> findByKbIdAndIdLessThanOrderByIdDesc(Long kbId, Long lastId,
                                                        org.springframework.data.domain.Pageable pageable);

    /** 某知识库某状态文档数（K8 体检·索引成功率用）。 */
    long countByKbIdAndStatus(Long kbId, Document.DocumentStatus status);
}
