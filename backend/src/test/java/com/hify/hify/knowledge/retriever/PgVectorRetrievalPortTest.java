package com.hify.hify.knowledge.retriever;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgVectorRetrievalPort 单测（M5/T4 语义单路 + K4 混合检索 R2）。
 *
 * <p>大白话：用 Mockito 把 PG 的 {@link NamedParameterJdbcTemplate} 和 MySQL 的 {@link DocumentRepository}
 * 都 mock 掉，不连真实库，专门验证"两条腿召回 + RRF 融合 + 跨库软删下推 + 小库/开关兜底"这套逻辑对不对。
 * 真连 PG/MySQL 的端到端验证交给 {@code PgVectorRetrievalIntegrationTest} 与 K10 题集打分。
 *
 * <p>注：混合检索走 {@link NamedParameterJdbcTemplate}（固定 3 参 {@code query(String, Map, RowMapper)}、
 * {@code IN (:list)} 由它展开），mock 时 arity 固定、不踩变参 stub 的坑。
 */
@ExtendWith(MockitoExtension.class)
class PgVectorRetrievalPortTest {

    @Mock
    private JdbcTemplate pgJdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate pgNamedJdbcTemplate;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private PgVectorRetrievalPort retrievalPort;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    @Captor
    private ArgumentCaptor<RowMapper<RetrievalPort.RetrievedChunk>> rowMapperCaptor;

    /** 捕获混合检索发出的所有 PG SQL（用于断言"未发 FTS 路"/"IN 占位正确"）。 */
    private final List<String> capturedSqls = new ArrayList<>();

    /** 捕获混合检索下推的命名参数（用于断言"软删 doc 未进入 IN 列表"）。 */
    private final List<MapSqlParameterSource> capturedParams = new ArrayList<>();

    private static final double TH = 0.0;

    @BeforeEach
    void resetCapture() {
        capturedSqls.clear();
        capturedParams.clear();
    }

    // ===================== 语义单路（T4，保留） =====================

    @Test
    void testRetrieve_queryAndKbIdAndTopKPassed_buildsCosineSqlWithFilterAndLimit() {
        when(pgJdbcTemplate.query(sqlCaptor.capture(), rowMapperCaptor.capture(), any(), any(), any(), any()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.91)));

        List<RetrievalPort.RetrievedChunk> result = retrievalPort.retrieve(new float[]{0.1f, 0.2f}, 1L, 5, TH);

        String sql = sqlCaptor.getValue().toLowerCase();
        assertTrue(sql.contains("1 - (embedding <=>"), "SQL 必须按余弦相似度（1 - 余弦距离）排序");
        assertTrue(sql.contains("kb_id ="), "SQL 必须按 kb_id 隔离");
        assertTrue(sql.contains("score >= "), "SQL 必须按相似度阈值过滤低于阈值的 chunk（T4 验收点3）");
        assertTrue(sql.contains("order by score desc"), "结果应降序");
        assertTrue(sql.contains("limit ?"), "必须限制返回条数");
        assertEquals(1, result.size());
        assertEquals(0.91, result.get(0).score());
    }

    @Test
    void testRetrieve_rowMapper_mapsFields_correctly() throws Exception {
        when(pgJdbcTemplate.query(any(), rowMapperCaptor.capture(), any(), any(), any(), any()))
                .thenReturn(List.of());

        retrievalPort.retrieve(new float[]{0.1f}, 1L, 3, TH);
        RowMapper<RetrievalPort.RetrievedChunk> mapper = rowMapperCaptor.getValue();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("document_id")).thenReturn("doc-x");
        when(rs.getInt("seq")).thenReturn(2);
        when(rs.getString("content")).thenReturn("片段内容");
        when(rs.getDouble("score")).thenReturn(0.77);

        RetrievalPort.RetrievedChunk chunk = mapper.mapRow(rs, 0);

        assertEquals("doc-x", chunk.documentId());
        assertEquals(2, chunk.chunkIndex());
        assertEquals("片段内容", chunk.content());
        assertEquals(0.77, chunk.score());
    }

    @Test
    void testRetrieve_emptyQuery_returnsEmptyList_withoutCallingPg() {
        List<RetrievalPort.RetrievedChunk> result = retrievalPort.retrieve(new float[0], 1L, 5, TH);
        assertTrue(result.isEmpty());
    }

    @Test
    void testRetrieve_sameContent_returnsTopKByCosine() {
        List<RetrievalPort.RetrievedChunk> rows = List.of(
                new RetrievalPort.RetrievedChunk("d1", 0, "与查询相同的文本", 0.99),
                new RetrievalPort.RetrievedChunk("d2", 1, "较相关片段", 0.80));
        when(pgJdbcTemplate.query(any(), any(RowMapper.class), any(), any(), any(), any())).thenReturn(rows);

        List<RetrievalPort.RetrievedChunk> topK = retrievalPort.retrieve(new float[]{0.1f, 0.2f}, 1L, 2, TH);

        assertEquals(2, topK.size(), "命中数应等于 top-k");
        assertEquals(0.99, topK.get(0).score(), 1e-9);
        assertEquals(0.80, topK.get(1).score(), 1e-9);
        assertEquals("与查询相同的文本", topK.get(0).content(), "同名内容（余弦最高）应排第一");
    }

    @Test
    void testRetrieve_otherKb_returnsEmpty() {
        List<RetrievalPort.RetrievedChunk> kb1 = List.of(new RetrievalPort.RetrievedChunk("d1", 0, "内容A", 0.9));
        when(pgJdbcTemplate.query(any(), any(RowMapper.class), any(), any(), any(), any())).thenAnswer(inv -> {
            Object kb = inv.getArgument(3);
            return kb.equals(1L) ? kb1 : List.of();
        });

        List<RetrievalPort.RetrievedChunk> got1 = retrievalPort.retrieve(new float[]{0.1f}, 1L, 5, TH);
        List<RetrievalPort.RetrievedChunk> got2 = retrievalPort.retrieve(new float[]{0.1f}, 2L, 5, TH);

        assertEquals(1, got1.size());
        assertTrue(got2.isEmpty(), "其它知识库(kb)不应命中本库数据");
    }

    // ===================== 混合检索（K4，R2 + P1） =====================

    private RagConfig ragConfig(boolean ftsEnabled, String tsConfig, int topK, int rrfK, double threshold) {
        // 构造参数：embeddingModel, vectorDim, chunkSize, chunkOverlap, retrievalTopK, finalTopN,
        //          rrfK, scoreThreshold, contextExpand, ftsEnabled, ftsTsConfig
        return new RagConfig("bge-m3", 1024, 400, 60, topK, topK, rrfK, threshold, 1, ftsEnabled, tsConfig);
    }

    private void stubEmbedding(String query) {
        when(embeddingService.embedSlices(List.of(query))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    /** 制造一个"大库"有效 doc 业务 id 列表（>=100 篇），使混合检索进入双路而非小库纯向量退化。
     * 注意：这里是业务 document_id（字符串 UUID），与 document_chunk.document_id 同口径，
     * 与 MySQL 侧 {@code findIdsByKbIdIn} 返回的 {@code d.documentId} 一致（非自增 Long 主键）。 */
    private List<String> hundredDocIds() {
        List<String> ids = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            ids.add(String.valueOf(i));
        }
        return ids;
    }

    /** 双路 stub：SQL 含 to_tsvector → 返回 ftsRows；否则返回 semRows。同时收集 SQL 与命名参数。 */
    private void stubDualPath(List<PgVectorRetrievalPort.RawHit> semRows,
                              List<PgVectorRetrievalPort.RawHit> ftsRows) {
        when(pgNamedJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    capturedSqls.add((String) inv.getArgument(0));
                    capturedParams.add((MapSqlParameterSource) inv.getArgument(1));
                    String sql = ((String) inv.getArgument(0)).toLowerCase();
                    return sql.contains("to_tsvector") ? ftsRows : semRows;
                });
    }

    @Test
    void testRetrieveHybrid_dualPathRrfFavorsBothHitChunk() {
        stubEmbedding("查询");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(hundredDocIds());
        // 语义：A 第1、B 第2；FTS：B 第1。B 两路都中 → RRF 累加应排第一
        List<PgVectorRetrievalPort.RawHit> sem = List.of(
                new PgVectorRetrievalPort.RawHit("dA", 1, "语义A", 0.90, RetrievalResult.RetrievalSource.SEMANTIC),
                new PgVectorRetrievalPort.RawHit("dB", 2, "语义B", 0.80, RetrievalResult.RetrievalSource.SEMANTIC));
        List<PgVectorRetrievalPort.RawHit> fts = List.of(
                new PgVectorRetrievalPort.RawHit("dB", 2, "语义B", 0.70, RetrievalResult.RetrievalSource.FTS));
        stubDualPath(sem, fts);

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(1L),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertEquals(2, res.size(), "A、B 都应进融合结果");
        assertEquals("dB", res.get(0).documentId(), "B 两路命中，RRF 分最高应排第一");
        assertEquals("dA", res.get(1).documentId());
        assertEquals(1, res.get(0).rank());
        assertEquals(2, res.get(1).rank());
        // B 来源优先标记 SEMANTIC（语义为权威相似度）
        assertEquals(RetrievalResult.RetrievalSource.SEMANTIC, res.get(0).source());
    }

    @Test
    void testRetrieveHybrid_exactCodeViaFts_sourceFts() {
        // 精确编号 "KPI-2026"：语义路空、FTS 路命中 → 来源标记 FTS
        stubEmbedding("KPI-2026");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(hundredDocIds());
        List<PgVectorRetrievalPort.RawHit> sem = List.of();
        List<PgVectorRetrievalPort.RawHit> fts = List.of(
                new PgVectorRetrievalPort.RawHit("dK", 1, "KPI-2026 指标定义", 0.5, RetrievalResult.RetrievalSource.FTS));
        stubDualPath(sem, fts);

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("KPI-2026", List.of(1L),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertEquals(1, res.size());
        assertEquals("dK", res.get(0).documentId());
        assertEquals(RetrievalResult.RetrievalSource.FTS, res.get(0).source(), "精确编号应走 FTS 路");
    }

    @Test
    void testRetrieveHybrid_synonymViaSemantic_sourceSemantic() {
        // 换说法 "辞职"：FTS 路空、语义路中（向量找到"离职"片段）→ 来源标记 SEMANTIC
        stubEmbedding("辞职");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(hundredDocIds());
        List<PgVectorRetrievalPort.RawHit> sem = List.of(
                new PgVectorRetrievalPort.RawHit("dL", 1, "离职流程说明", 0.85, RetrievalResult.RetrievalSource.SEMANTIC));
        List<PgVectorRetrievalPort.RawHit> fts = List.of();
        stubDualPath(sem, fts);

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("辞职", List.of(1L),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertEquals(1, res.size());
        assertEquals("dL", res.get(0).documentId());
        assertEquals(RetrievalResult.RetrievalSource.SEMANTIC, res.get(0).source(), "换说法应走语义路");
    }

    @Test
    void testRetrieveHybrid_crossLibSoftDelete_twoStage_excludesDeletedDoc() {
        // 挂载库含 3 个 doc，其中 doc3("doc3") 已软删 → MySQL 第一步（@SQLRestriction）只回
        // 未软删的 [doc1("doc1"), doc2("doc2")] 业务 id（注意：是 document_chunk.document_id 同口径的
        // 业务 UUID 字符串，不是自增 Long 主键）
        stubEmbedding("查询");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(List.of("doc1", "doc2"));
        List<PgVectorRetrievalPort.RawHit> sem = List.of(
                new PgVectorRetrievalPort.RawHit("doc1", 1, "A", 0.9, RetrievalResult.RetrievalSource.SEMANTIC),
                new PgVectorRetrievalPort.RawHit("doc2", 1, "B", 0.8, RetrievalResult.RetrievalSource.SEMANTIC));
        stubDualPath(sem, List.of());

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(1L),
                ragConfig(false, "zhparser_cfg", 5, 60, TH)); // fts=false 走纯向量，仍验证 IN 下推内容

        // SQL 必须用命名参数 IN (:validDocIds)（非拼接），且下推列表只有有效 doc 的业务 id，软删 doc3 不进入
        boolean usedNamedIn = capturedSqls.stream()
                .anyMatch(s -> s.toLowerCase().contains("document_id in (:validdocids)"));
        assertTrue(usedNamedIn, "PG 查询应使用命名参数 IN (:validDocIds)，禁止拼接");
        @SuppressWarnings("unchecked")
        List<String> pushed = (List<String>) capturedParams.get(0).getValues().get("validDocIds");
        assertEquals(List.of("doc1", "doc2"), pushed,
                "下推的有效 id 应为未软删文档的业务 document_id（与 document_chunk.document_id 同口径，非自增主键）");
        assertFalse(pushed.contains("doc3"), "软删文档 doc3 不得进入下推列表（P1 生效）");
        for (RetrievalResult r : res) {
            assertFalse(r.documentId().equals("doc3"), "软删文档 doc3 不得命中");
        }
    }

    @Test
    void testRetrieveHybrid_emptyValidDocIds_noPgQuery() {
        // 第一步筛出空（全部软删/未挂载）→ 不应发任何 PG 查询
        stubEmbedding("查询");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(List.of());

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(1L),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertTrue(res.isEmpty());
        verify(pgNamedJdbcTemplate, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void testRetrieveHybrid_noMountedKb_noEmbedNoPg() {
        // 没有任何挂载库 → 不向量化、不发 PG 查询
        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertTrue(res.isEmpty());
        verify(embeddingService, never()).embedSlices(anyList());
        verify(pgNamedJdbcTemplate, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void testRetrieveHybrid_ftsDisabled_pureVector_noFtsSql() {
        // fts.enabled=false → 纯向量单路，绝不发 FTS(to_tsvector) SQL
        stubEmbedding("查询");
        when(documentRepository.findIdsByKbIdIn(List.of(1L))).thenReturn(List.of("doc1", "doc2"));
        List<PgVectorRetrievalPort.RawHit> sem = List.of(
                new PgVectorRetrievalPort.RawHit("dA", 1, "A", 0.9, RetrievalResult.RetrievalSource.SEMANTIC));
        stubDualPath(sem, List.of());

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(1L),
                ragConfig(false, "zhparser_cfg", 5, 60, TH));

        assertEquals(1, res.size());
        assertEquals(RetrievalResult.RetrievalSource.SEMANTIC, res.get(0).source());
        boolean issuedFts = capturedSqls.stream().anyMatch(s -> s.toLowerCase().contains("to_tsvector"));
        assertFalse(issuedFts, "fts 关闭时不得发 FTS 路 SQL");
    }

    @Test
    void testRetrieveHybrid_smallLibrary_pureVector_noFtsSql() {
        // 有效文档数 < 100 → 小库退化纯向量，即使 fts 开启也不发 FTS SQL
        stubEmbedding("查询");
        when(documentRepository.findIdsByKbIdIn(List.of(1L)))
                .thenReturn(List.of("1", "2", "3")); // 仅 3 篇，< 100
        List<PgVectorRetrievalPort.RawHit> sem = List.of(
                new PgVectorRetrievalPort.RawHit("dA", 1, "A", 0.9, RetrievalResult.RetrievalSource.SEMANTIC));
        stubDualPath(sem, List.of());

        List<RetrievalResult> res = retrievalPort.retrieveHybrid("查询", List.of(1L),
                ragConfig(true, "zhparser_cfg", 5, 60, TH));

        assertEquals(1, res.size());
        assertEquals(RetrievalResult.RetrievalSource.SEMANTIC, res.get(0).source());
        boolean issuedFts = capturedSqls.stream().anyMatch(s -> s.toLowerCase().contains("to_tsvector"));
        assertFalse(issuedFts, "小库退化纯向量时不得发 FTS 路 SQL");
    }
}
