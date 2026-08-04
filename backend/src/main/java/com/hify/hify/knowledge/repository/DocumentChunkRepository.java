package com.hify.hify.knowledge.repository;

import com.hify.hify.knowledge.util.PgVectorUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
