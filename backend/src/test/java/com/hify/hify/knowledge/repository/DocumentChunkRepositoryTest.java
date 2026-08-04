package com.hify.hify.knowledge.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * DocumentChunkRepository 单元测试（M5 T3）：mock JdbcTemplate，验证 SQL 拼接（INSERT ?::vector 强转）。
 * 表结构建表已由 Flyway 迁移负责（db/pg/migration/V2__knowledge_chunk.sql），本单测不再覆盖运行时 CREATE TABLE。
 */
@ExtendWith(MockitoExtension.class)
class DocumentChunkRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    private DocumentChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DocumentChunkRepository(jdbcTemplate);
    }

    @Test
    void testSaveChunk_passesVectorCast() {
        repository.saveChunk("doc1", 1L, 0, "hello", new float[]{0.1f, 0.2f});

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), params.capture());

        String sqlStr = sql.getValue().toLowerCase();
        assertTrue(sqlStr.contains("insert into document_chunk"));
        assertTrue(sqlStr.contains("::vector"), "embedding 列应走 ?::vector 强转");

        Object[] p = params.getValue();
        assertEquals("doc1", p[0]);
        assertEquals(1L, p[1]);
        assertEquals(0, p[2]);
        assertEquals("hello", p[3]);
        assertEquals("[0.1,0.2]", p[4]);
    }
}
