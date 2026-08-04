package com.hify.hify.knowledge.retriever;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.service.EmbeddingService;
import com.hify.hify.knowledge.util.PgVectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL + pgvector 的检索实现（M5/T4 语义单路 + K4 混合检索 R2）。
 *
 * <p>大白话：
 * <ul>
 *   <li><b>语义单路</b>（保留 T4 能力）：用 pg 向量做余弦最近邻，按 {@code kb_id} 隔离，
 *       阈值过滤低相似度 chunk。供 {@code KnowledgeService.retrieve} 等既有单库调用。</li>
 *   <li><b>混合检索</b>（K4 新增 {@link #retrieveHybrid}）：一条查询同时走两条腿——
 *       语义向量路（pgvector 余弦找"意思像"的）+ 关键词路（PG FTS 找"字面对"的），
 *       两路各取 top-k 后按 <b>RRF（Reciprocal Rank Fusion）</b> 融合——两路分数单位不可比，
 *       所以只投"名次"：{@code 1/(rrf_k + rank)} 求和再排序。跨库软删走两段式
 *       （MySQL 先筛有效 id → 下推 PG {@code IN}），删了的库/文档搜不出来（P1）。</li>
 * </ul>
 *
 * <p>§4.9 调用留痕：检索前后打 INFO 日志（命中数 / 两路命中数 / 耗时），便于可观测。
 */
@Repository
@Slf4j
public class PgVectorRetrievalPort implements RetrievalPort {

    private final JdbcTemplate pgJdbcTemplate;
    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final NamedParameterJdbcTemplate pgNamedJdbcTemplate;

    /** 余弦相似度列名（语义单路用）。 */
    private static final String SCORE_COLUMN = "score";

    /**
     * 小库退化阈值（K4，需求 §5.2）：挂载库内<b>有效文档数</b> &lt; 此值时，FTS 路收益不足以抵消开销，
     * 退化为纯向量单路。这是"检索策略启发式"而非业务魔法数字——与切块大小/阈值（须进 RagConfig）不同类，
     * 故在此以具名常量固定，不在代码里散落字面量。
     */
    private static final int SMALL_LIBRARY_MAX_DOCS = 100;

    /** 语义单路 SQL：向量、有效 id 列表、阈值、条数均走命名参数（§7.2 防注入）。 */
    private static final String SEMANTIC_SQL =
            "SELECT document_id, seq, content, (1 - (embedding <=> :vec::vector)) AS " + SCORE_COLUMN
            + " FROM document_chunk WHERE document_id IN (:validDocIds)"
            + " AND (1 - (embedding <=> :vec::vector)) >= :threshold ORDER BY " + SCORE_COLUMN + " DESC LIMIT :limit";

    /** 关键词路 SQL：PG FTS，分词器与查询均走命名参数（:tsConfig / :q），绝不拼接用户输入（§7.2）。 */
    private static final String FTS_SQL =
            "SELECT document_id, seq, content,"
            + " ts_rank(to_tsvector(:tsConfig, content), plainto_tsquery(:tsConfig, :q)) AS " + SCORE_COLUMN
            + " FROM document_chunk WHERE document_id IN (:validDocIds)"
            + " AND to_tsvector(:tsConfig, content) @@ plainto_tsquery(:tsConfig, :q)"
            + " ORDER BY " + SCORE_COLUMN + " DESC LIMIT :limit";

    public PgVectorRetrievalPort(@Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                                 EmbeddingService embeddingService,
                                 DocumentRepository documentRepository,
                                 @Qualifier("pgNamedJdbcTemplate") NamedParameterJdbcTemplate pgNamedJdbcTemplate) {
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.pgNamedJdbcTemplate = pgNamedJdbcTemplate;
    }

    // ===================== 语义单路（保留 T4 能力，不重写） =====================

    @Override
    public List<RetrievedChunk> retrieve(float[] queryEmbedding, Long kbId, int topK, double threshold) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        log.info("retrieve start kbId={} topK={} threshold={} dim={}", kbId, topK, threshold, queryEmbedding.length);
        // 先算余弦相似度 score，再用 WHERE score >= ? 过滤低于阈值的 chunk（T4 验收点3：阈值真正生效）
        String sql = "SELECT document_id, seq, content, score FROM ("
                + " SELECT document_id, seq, content, 1 - (embedding <=> ?::vector) AS " + SCORE_COLUMN
                + " FROM document_chunk WHERE kb_id = ?"
                + ") t WHERE " + SCORE_COLUMN + " >= ? ORDER BY " + SCORE_COLUMN + " DESC LIMIT ?";
        try {
            List<RetrievedChunk> hits = pgJdbcTemplate.query(
                    sql, ROW_MAPPER, PgVectorUtils.toPgVector(queryEmbedding), kbId, threshold, topK);
            log.info("retrieve done kbId={} topK={} threshold={} hits={}", kbId, topK, threshold, hits.size());
            return hits;
        } catch (DataAccessException e) {
            // 捕获具体异常（规则11），翻译成统一业务异常（规则14：必须绑定 ErrorCode）。
            BizException ex = new BizException(ErrorCode.RETRIEVAL_FAILED, "kbId=" + kbId);
            ex.initCause(e);
            throw ex;
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String queryText, Long kbId, int topK, double threshold) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        // 端口内完成向量化，调用方（conversation）免感知 EmbeddingService（§3.2 解耦）
        List<float[]> vectors = embeddingService.embedSlices(List.of(queryText));
        if (vectors.isEmpty()) {
            return List.of();
        }
        return retrieve(vectors.get(0), kbId, topK, threshold);
    }

    // ===================== 混合检索（K4，R2 + 跨库软删 P1） =====================

    @Override
    public List<RetrievalResult> retrieveHybrid(String queryText, List<Long> mountedKbIds, RagConfig ragConfig) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        // 最外层护栏：没有任何挂载库，连向量化都省了（P1 隔离 + 省一次 EmbeddingService 调用）
        if (mountedKbIds == null || mountedKbIds.isEmpty()) {
            log.info("retrieveHybrid skip: no mounted kb, no embedding / no PG query");
            return List.of();
        }
        // 端口内完成向量化（与语义单路一致，调用方免感知 EmbeddingService）
        List<float[]> vectors = embeddingService.embedSlices(List.of(queryText));
        if (vectors.isEmpty()) {
            return List.of();
        }
        return retrieveHybrid(vectors.get(0), queryText, mountedKbIds, ragConfig);
    }

    /**
     * 混合检索核心（已向量化）。
     *
     * <p>两步软删下推 P1 + 双路召回 + RRF 融合；空挂载/空有效 id 直接返回，绝不发无意义的 PG 查询。
     */
    public List<RetrievalResult> retrieveHybrid(float[] queryEmbedding, String queryText,
                                                List<Long> mountedKbIds, RagConfig ragConfig) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        // 第一段护栏：没有任何挂载库，谈不上检索，直接返回（P1 隔离的最外层兜底）
        if (mountedKbIds == null || mountedKbIds.isEmpty()) {
            log.info("retrieveHybrid skip: no mounted kb, no PG query issued");
            return List.of();
        }
        // 跨库软删·第一段（MySQL）：按挂载库筛出"未被软删"的有效 document_id（业务 UUID 字符串，
        // 与 document_chunk.document_id 同口径；由 DocumentRepository.findIdsByKbIdIn 返回 d.documentId）
        List<String> validDocIds = documentRepository.findIdsByKbIdIn(mountedKbIds);
        // 第二段护栏：有效 id 为空（全部软删/未挂载），直接返回，绝对不发 `IN ()` 这种非法 SQL
        if (validDocIds.isEmpty()) {
            log.info("retrieveHybrid skip: validDocIds empty (all soft-deleted/unmounted), no PG query issued");
            return List.of();
        }

        int topK = ragConfig.retrievalTopK();
        int rrfK = ragConfig.rrfK();
        double threshold = ragConfig.scoreThreshold();
        boolean ftsEnabled = ragConfig.ftsEnabled();
        boolean smallLibrary = validDocIds.size() < SMALL_LIBRARY_MAX_DOCS;
        long start = System.currentTimeMillis();

        // 兜底两级（需求 §5.2）：①zhparser 缺失→探测器已自动降级 tsConfig=simple，FTS 路照跑；
        // ②fts.enabled=false（人工总开关）→ 纯向量；③小库退化纯向量。三者都只走语义单路。
        if (!ftsEnabled || smallLibrary) {
            List<RawHit> sem = semanticSearch(queryEmbedding, validDocIds, topK * 2, threshold);
            List<RetrievalResult> result = rankSinglePath(sem, topK);
            log.info("retrieveHybrid pure-vector path (ftsEnabled={} smallLibrary={}) topK={} semHits={} costMs={}",
                    ftsEnabled, smallLibrary, topK, sem.size(), System.currentTimeMillis() - start);
            return result;
        }

        // 双路召回
        List<RawHit> sem = semanticSearch(queryEmbedding, validDocIds, topK, threshold);
        List<RawHit> fts = ftsSearch(queryText, validDocIds, topK, ragConfig.ftsTsConfig());
        List<RetrievalResult> result = rrfFuse(sem, fts, rrfK, topK);
        log.info("retrieveHybrid dual-path topK={} semHits={} ftsHits={} fused={} costMs={}",
                topK, sem.size(), fts.size(), result.size(), System.currentTimeMillis() - start);
        return result;
    }

    /** 语义向量路召回（已按余弦降序）。 */
    private List<RawHit> semanticSearch(float[] queryEmbedding, List<String> validDocIds, int limit, double threshold) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("vec", PgVectorUtils.toPgVector(queryEmbedding)); // 向量占位（:vec::vector 绑定参数，非拼接）
        params.addValue("validDocIds", validDocIds);                     // 命名参数 + IN(:list) 自动展开
        params.addValue("threshold", threshold);
        params.addValue("limit", limit);
        try {
            return pgNamedJdbcTemplate.query(SEMANTIC_SQL, params, SEMANTIC_MAPPER);
        } catch (DataAccessException e) {
            throw toBiz(e, validDocIds);
        }
    }

    /** 关键词 FTS 路召回（已按 ts_rank 降序）。分词器与查询均绑定命名参数，绝不拼接用户输入（§7.2）。 */
    private List<RawHit> ftsSearch(String queryText, List<String> validDocIds, int limit, String tsConfig) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tsConfig", tsConfig);    // 分词器（由 K2 探针决定：zhparser_cfg / simple）
        params.addValue("q", queryText);         // 用户原始查询，绑定参数防注入
        params.addValue("validDocIds", validDocIds);
        params.addValue("limit", limit);
        try {
            return pgNamedJdbcTemplate.query(FTS_SQL, params, FTS_MAPPER);
        } catch (DataAccessException e) {
            throw toBiz(e, validDocIds);
        }
    }

    /** RRF 融合：两路按名次投票 {@code 1/(rrfK + rank)} 求和，按综合分降序取 topK。 */
    private List<RetrievalResult> rrfFuse(List<RawHit> sem, List<RawHit> fts, int rrfK, int topK) {
        Map<String, Fused> merged = new LinkedHashMap<>();
        for (int i = 0; i < sem.size(); i++) {
            RawHit h = sem.get(i);
            double rrf = 1.0 / (rrfK + (i + 1));
            merged.put(keyOf(h), new Fused(h, rrf, true, false));
        }
        for (int j = 0; j < fts.size(); j++) {
            RawHit h = fts.get(j);
            double rrf = 1.0 / (rrfK + (j + 1));
            Fused cur = merged.get(keyOf(h));
            if (cur == null) {
                merged.put(keyOf(h), new Fused(h, rrf, false, true));
            } else {
                cur.rrf += rrf;       // 同一 chunk 两路都命中 → 名次分累加
                cur.fromFts = true;   // 标记为"两路皆有"，来源仍优先标 SEMANTIC（语义为权威相似度）
            }
        }
        List<Fused> sorted = new ArrayList<>(merged.values());
        sorted.sort((a, b) -> Double.compare(b.rrf, a.rrf));
        List<RetrievalResult> out = new ArrayList<>();
        int n = Math.min(sorted.size(), topK);
        for (int i = 0; i < n; i++) {
            Fused f = sorted.get(i);
            RetrievalResult.RetrievalSource src = f.fromSemantic
                    ? RetrievalResult.RetrievalSource.SEMANTIC
                    : RetrievalResult.RetrievalSource.FTS;
            out.add(new RetrievalResult(f.hit.documentId(), f.hit.chunkIndex(),
                    f.hit.content(), f.rrf, i + 1, src));
        }
        return out;
    }

    /** 纯向量单路排名：直接按余弦降序赋名次（单路 RRF 退化为余弦排名）。 */
    private List<RetrievalResult> rankSinglePath(List<RawHit> hits, int topK) {
        List<RetrievalResult> out = new ArrayList<>();
        int n = Math.min(hits.size(), topK);
        for (int i = 0; i < n; i++) {
            RawHit h = hits.get(i);
            out.add(new RetrievalResult(h.documentId(), h.chunkIndex(), h.content(),
                    h.score(), i + 1, RetrievalResult.RetrievalSource.SEMANTIC));
        }
        return out;
    }

    private static String keyOf(RawHit h) {
        return h.documentId() + "#" + h.chunkIndex();
    }

    private static BizException toBiz(DataAccessException e, List<String> validDocIds) {
        BizException ex = new BizException(ErrorCode.RETRIEVAL_FAILED, "validDocIds=" + validDocIds.size());
        ex.initCause(e);
        return ex;
    }

    // ===================== RowMapper / 内部载体 =====================

    private static final RowMapper<RetrievedChunk> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static RetrievedChunk mapRow(ResultSet rs) throws SQLException {
        return new RetrievedChunk(
                rs.getString("document_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getDouble(SCORE_COLUMN));
    }

    private static final RowMapper<RawHit> SEMANTIC_MAPPER = (rs, rowNum) -> new RawHit(
            rs.getString("document_id"), rs.getInt("seq"), rs.getString("content"),
            rs.getDouble(SCORE_COLUMN), RetrievalResult.RetrievalSource.SEMANTIC);

    private static final RowMapper<RawHit> FTS_MAPPER = (rs, rowNum) -> new RawHit(
            rs.getString("document_id"), rs.getInt("seq"), rs.getString("content"),
            rs.getDouble(SCORE_COLUMN), RetrievalResult.RetrievalSource.FTS);

    /** 单路召回的原始命中（融合前），包级可见以便单测构造。 */
    static record RawHit(String documentId, int chunkIndex, String content, double score,
                         RetrievalResult.RetrievalSource source) {
    }

    /** RRF 融合的中间态：原始命中 + 综合名次分 + 来源标记。 */
    private static final class Fused {
        RawHit hit;
        double rrf;
        boolean fromSemantic;
        boolean fromFts;

        Fused(RawHit hit, double rrf, boolean fromSemantic, boolean fromFts) {
            this.hit = hit;
            this.rrf = rrf;
            this.fromSemantic = fromSemantic;
            this.fromFts = fromFts;
        }
    }
}
