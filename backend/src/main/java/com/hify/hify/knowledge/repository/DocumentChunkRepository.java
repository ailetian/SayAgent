package com.hify.hify.knowledge.repository;

import com.hify.hify.knowledge.util.PgVectorUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 切片向量仓储（M5 T3）——落 pg 向量库，不走 JPA（pg 是第二数据源，且存 vector 列）。
 *
 * <p>大白话：document_chunk 表在 pg 里，用 JdbcTemplate(pgDataSource) 直接写；
 * embedding 列是 pgvector 的 vector(1024)，写入时把 float[] 拼成 {@code '[...]'} 再用 {@code ?::vector} 强转。
 * 表结构由 Flyway 迁移（db/pg/migration/V2__knowledge_chunk.sql）负责，禁止在代码里 CREATE TABLE（§9）。
 */
@Repository
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkRepository(@Qualifier("pgJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写一条切片向量（pgvector 用 ?::vector 强转字符串）。 */
    public void saveChunk(String documentId, Long kbId, int seq, String content, float[] vector) {
        String sql = "INSERT INTO document_chunk (document_id, kb_id, seq, content, embedding) "
                + "VALUES (?, ?, ?, ?, ?::vector)";
        jdbcTemplate.update(sql, documentId, kbId, seq, content, PgVectorUtils.toPgVector(vector));
    }

    /**
     * 原子"删旧 + 插新"替换某文档的全部切片（K11 版本治理 / 防半套 chunk）。
     *
     * <p>大白话：重传同一篇文档时，先把这个文档旧的所有 chunk+向量清掉，再一次性批量写新的，
     * 整个动作落在 {@code pgTransactionManager} 这一个 pg 本地事务里——要么新旧都换好，
     * 要么一条都不留（中途异常整体回滚），绝不让"新旧两版碎片混在库里"误导检索。
     * 批量写入（{@code batchUpdate}）比循环单条插更高效（K11 缺陷 K）。
     *
     * @param documentId 文档业务 id（与 document_chunk.document_id 同口径）
     * @param kbId       知识库 id
     * @param rows       新切片（seq + 文本 + 向量），按 seq 升序
     */
    @Transactional("pgTransactionManager")
    public void replaceChunks(String documentId, Long kbId, List<ChunkRow> rows) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO document_chunk (document_id, kb_id, seq, content, embedding) "
                + "VALUES (?, ?, ?, ?, ?::vector)";
        jdbcTemplate.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
            ps.setString(1, documentId);
            ps.setLong(2, kbId);
            ps.setInt(3, row.seq());
            ps.setString(4, row.content());
            ps.setString(5, PgVectorUtils.toPgVector(row.vector()));
        });
    }

    /**
     * 删某文档的全部切片向量（P5 重传删旧 / K11 防半套 chunk）。
     *
     * <p>大白话：重传同一文件时，先把它旧的所有 chunk+向量清掉，再写新的，
     * 绝不让"新旧两版碎片混在库里"误导检索。STORE 阶段每次都先删后写，
     * 因此重传和断点重试的 STORE 都是幂等的（重复跑不会翻倍）。
     *
     * @param documentId 文档业务 id（与 document_chunk.document_id 同口径）
     */
    public void deleteByDocumentId(String documentId) {
        String sql = "DELETE FROM document_chunk WHERE document_id = ?";
        jdbcTemplate.update(sql, documentId);
    }

    /** 按知识库查全部切片（T4 检索前置，这里先提供基础读取；embedding 以字符串形式返回）。 */
    public List<DocumentChunk> findByKbId(Long kbId) {
        String sql = "SELECT document_id, kb_id, seq, content, embedding "
                + "FROM document_chunk WHERE kb_id = ? ORDER BY seq";
        return jdbcTemplate.query(sql, (rs, i) -> new DocumentChunk(
                rs.getString("document_id"),
                rs.getLong("kb_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getString("embedding")), kbId);
    }

    /**
     * 按文档 + seq 范围取切片（K5 R5 Small-to-Big 上下文扩展）。
     *
     * <p>大白话：命中一块（seq=N）后，把前 {@code expand} 块（N-expand..N-1）和后 {@code expand} 块
     * （N+1..N+expand）一起捞出来拼成大块喂给大模型，避免「一块太碎、LLM 看不见前后文」。
     * 范围用命名参数 {@code :lo}/{:hi} 防注入（§7.2）；结果按 seq 升序，调用方直接拼接即可。
     *
     * @param documentId 文档业务 id（与 document_chunk.document_id 同口径）
     * @param lo         最小 seq（含），通常 = 命中 seq - expand
     * @param hi         最大 seq（含），通常 = 命中 seq + expand
     * @return 该范围内的切片（按 seq 升序）；范围内无数据则返回空列表，不返回 null
     */
    public List<DocumentChunk> findByDocumentIdAndSeqBetween(String documentId, int lo, int hi) {
        String sql = "SELECT document_id, kb_id, seq, content, embedding "
                + "FROM document_chunk WHERE document_id = ? AND seq BETWEEN ? AND ? ORDER BY seq";
        return jdbcTemplate.query(sql, (rs, i) -> new DocumentChunk(
                rs.getString("document_id"),
                rs.getLong("kb_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getString("embedding")), documentId, lo, hi);
    }

    /** 向量切片读取结果（轻量 DTO，非 JPA 实体；embedding 为 pg 返回的字符串表示）。 */
    public record DocumentChunk(String documentId, Long kbId, int seq, String content, String embedding) {
    }

    /** 单条待写入切片（seq + 文本 + 向量），供 {@link #replaceChunks} 批量写入。 */
    public record ChunkRow(int seq, String content, float[] vector) {
    }
}
