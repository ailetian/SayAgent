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

    /** 向量切片读取结果（轻量 DTO，非 JPA 实体；embedding 为 pg 返回的字符串表示）。 */
    public record DocumentChunk(String documentId, Long kbId, int seq, String content, String embedding) {
    }
}
