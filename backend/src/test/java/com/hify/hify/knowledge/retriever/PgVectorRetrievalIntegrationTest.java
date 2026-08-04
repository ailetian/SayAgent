package com.hify.hify.knowledge.retriever;

import com.hify.hify.knowledge.retriever.RetrievalPort.RetrievedChunk;
import com.hify.hify.knowledge.util.PgVectorUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pg 检索链路真实集成测试（替代原 mock 版 PgVectorRetrievalPortTest）。
 *
 * <p>大白话：@SpringBootTest 启动真实 Spring 上下文，PgSchemaMigrator 会自动对 docker 里的
 * PostgreSQL（hify_vector 库，hify 为超管）执行 PG Flyway —— 启用 vector 扩展并建 document_chunk 表。
 * 本测试往真实表里插 1024 维向量，调用 PgVectorRetrievalPort.retrieve 跑真实的 {@code 1 - (embedding <=> ?::vector)}
 * 余弦查询，验证四件事：余弦降序、kb_id 隔离、阈值真正过滤、topK 限制。
 *
 * <p>与 CLAUDE.md §7.10 规则35 一致：方法名 test方法_场景_预期。
 */
@SpringBootTest
class PgVectorRetrievalIntegrationTest {

    @Autowired
    private PgVectorRetrievalPort retrievalPort;

    @Autowired
    @Qualifier("pgJdbcTemplate")
    private JdbcTemplate pg;

    private static final long TEST_KB = 99101L;
    private static final long OTHER_KB = 99102L;
    private static final int DIM = 1024;

    @BeforeEach
    void cleanBefore() {
        pg.update("DELETE FROM document_chunk WHERE kb_id = ?", TEST_KB);
        pg.update("DELETE FROM document_chunk WHERE kb_id = ?", OTHER_KB);
    }

    @AfterEach
    void cleanAfter() {
        pg.update("DELETE FROM document_chunk WHERE kb_id = ?", TEST_KB);
        pg.update("DELETE FROM document_chunk WHERE kb_id = ?", OTHER_KB);
    }

    /** 构造 1024 维向量：除前两维外全为 0.01，方便手算余弦相似度。 */
    private float[] vec(float first, float second) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = 0.01f;
        }
        v[0] = first;
        v[1] = second;
        return v;
    }

    /** 与检索查询完全一致的向量：相似度应为 1.0。 */
    private float[] queryVec() {
        return vec(1.0f, 0.01f);
    }

    private void insert(long kbId, String docId, int seq, float[] embedding, String content) {
        pg.update(
                "INSERT INTO document_chunk (document_id, kb_id, seq, content, embedding) "
                        + "VALUES (?, ?, ?, ?, ?::vector)",
                docId, kbId, seq, content, PgVectorUtils.toPgVector(embedding));
    }

    @Test
    void testRetrieve_twoSameKbChunks_returnsOrderedBySimilarityDesc() {
        float[] q = queryVec();
        insert(TEST_KB, "docA", 1, vec(1.0f, 0.01f), "A");   // 相似度 ~1.0
        insert(TEST_KB, "docE", 2, vec(1.0f, 0.5f), "E");    // 相似度 ~0.91
        insert(TEST_KB, "docD", 3, vec(0.0f, 0.01f), "D");   // 相似度 ~0.30

        List<RetrievedChunk> hits = retrievalPort.retrieve(q, TEST_KB, 10, 0.0);

        assertEquals(3, hits.size());
        assertEquals("A", hits.get(0).content());
        assertEquals("E", hits.get(1).content());
        assertEquals("D", hits.get(2).content());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertTrue(hits.get(1).score() > hits.get(2).score());
    }

    @Test
    void testRetrieve_otherKbChunk_excludedByKbFilter() {
        float[] q = queryVec();
        insert(TEST_KB, "docA", 1, vec(1.0f, 0.01f), "A");
        insert(OTHER_KB, "docC", 1, vec(1.0f, 0.01f), "C");  // 向量与查询一致，但属另一个 kb

        List<RetrievedChunk> hits = retrievalPort.retrieve(q, TEST_KB, 10, 0.0);

        assertEquals(1, hits.size());
        assertEquals("A", hits.get(0).content());
    }

    @Test
    void testRetrieve_lowSimilarityChunk_excludedByThreshold() {
        float[] q = queryVec();
        insert(TEST_KB, "docA", 1, vec(1.0f, 0.01f), "A");
        insert(TEST_KB, "docE", 2, vec(1.0f, 0.5f), "E");
        insert(TEST_KB, "docD", 3, vec(0.0f, 0.01f), "D");   // ~0.30 < 0.6

        List<RetrievedChunk> hits = retrievalPort.retrieve(q, TEST_KB, 10, 0.6);

        assertEquals(2, hits.size());
        assertEquals("A", hits.get(0).content());
        assertEquals("E", hits.get(1).content());
    }

    @Test
    void testRetrieve_topKLimit_respected() {
        float[] q = queryVec();
        insert(TEST_KB, "docA", 1, vec(1.0f, 0.01f), "A");
        insert(TEST_KB, "docE", 2, vec(1.0f, 0.5f), "E");

        List<RetrievedChunk> hits = retrievalPort.retrieve(q, TEST_KB, 1, 0.0);

        assertEquals(1, hits.size());
        assertEquals("A", hits.get(0).content());
    }
}
