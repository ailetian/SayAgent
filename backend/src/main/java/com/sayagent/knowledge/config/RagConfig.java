package com.sayagent.knowledge.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次检索/切片真正生效的 RAG 参数快照（K2）。
 *
 * <p>大白话：{@link RagProperties} 是"出厂默认档位"，某个知识库可以在 {@code knowledge_base.rag_config}
 * 里只写它想改的那几项；本类负责把两者叠在一起算出"这个库这次到底用什么参数"——
 * <b>库级有值用库级，没写就回退全局</b>。算完就不可变（record），谁也改不了它，避免检索途中被并发改参数。
 *
 * <p>为什么库级存 JSON 而不是加列：RAG 参数会不断增减，加一个参数就改一次表结构不现实（需求 §8.1）。
 * 代价是 JSON 里的键名可能写错/写乱，所以本类解析时：解析失败不抛异常、只回退全局并打 WARN，
 * 绝不让一个手滑的 JSON 把整条检索链路打挂。
 *
 * <p>键名同时兼容 {@code snake_case}（需求文档写法，如 {@code chunk_size}）与 {@code camelCase}
 * （前端 JS 写法，如 {@code chunkSize}），两种都能读到。
 *
 * <p><b>唯一例外：{@code vector_dim} 不接受库级覆盖</b>。它与 pgvector 的 {@code vector(1024)} 列类型
 * 硬绑定，某个库单独改维度只会写不进去或永远检索不出来，因此库级写了也只打 WARN 并忽略（需求 §6）。
 *
 * @param embeddingModel embedding 模型名（建库参数）
 * @param vectorDim      向量维度（建库参数，禁运行时改）
 * @param chunkSize      切块大小，字符（建库参数）
 * @param chunkOverlap   切块重叠，字符（建库参数）
 * @param retrievalTopK  单路召回条数（检索参数）
 * @param finalTopN      最终喂模型片段数（检索参数）
 * @param rrfK           RRF 融合常数（检索参数）
 * @param scoreThreshold 相似度阈值，低于则拒答（检索参数）
 * @param contextExpand  上下文扩展块数（检索参数）
 * @param ftsEnabled     是否启用 FTS 路
 * @param ftsTsConfig    FTS 分词配置名（zhparser_cfg / simple，由 {@link ZhparserProbe} 决定）
 */
public record RagConfig(
        String embeddingModel,
        int vectorDim,
        int chunkSize,
        int chunkOverlap,
        int retrievalTopK,
        int finalTopN,
        int rrfK,
        double scoreThreshold,
        int contextExpand,
        boolean ftsEnabled,
        String ftsTsConfig) {

    private static final Logger LOG = LoggerFactory.getLogger(RagConfig.class);

    /** 复用同一个 ObjectMapper：线程安全且构造开销大，禁止每次解析 new 一个。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_EMBEDDING_MODEL = "embedding_model";
    private static final String KEY_VECTOR_DIM = "vector_dim";
    private static final String KEY_CHUNK_SIZE = "chunk_size";
    private static final String KEY_CHUNK_OVERLAP = "chunk_overlap";
    private static final String KEY_RETRIEVAL_TOP_K = "retrieval_top_k";
    private static final String KEY_FINAL_TOP_N = "final_top_n";
    private static final String KEY_RRF_K = "rrf_k";
    private static final String KEY_SCORE_THRESHOLD = "score_threshold";
    private static final String KEY_CONTEXT_EXPAND = "context_expand";
    private static final String KEY_FTS_ENABLED = "fts_enabled";
    private static final String KEY_FTS_TS_CONFIG = "fts_ts_config";

    private static final char UNDERSCORE = '_';

    /**
     * 把 {@code application.yml} 的 {@code rag:} 段（全局默认）转成参数快照。
     *
     * @param properties 全局默认配置，不可为 null
     * @return 全局默认参数快照
     */
    public static RagConfig fromGlobal(RagProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("RagProperties 不能为空");
        }
        RagProperties.Fts fts = properties.getFts() == null ? new RagProperties.Fts() : properties.getFts();
        return new RagConfig(
                properties.getEmbeddingModel(),
                properties.getVectorDim(),
                properties.getChunkSize(),
                properties.getChunkOverlap(),
                properties.getRetrievalTopK(),
                properties.getFinalTopN(),
                properties.getRrfK(),
                properties.getScoreThreshold(),
                properties.getContextExpand(),
                fts.isEnabled(),
                fts.getTsConfig());
    }

    /**
     * 用库级 JSON 覆盖全局默认，算出最终生效参数。
     *
     * <p>JSON 为空、非对象或解析失败时一律原样返回 {@code base}（打 WARN，不抛异常）——
     * 参数配错的后果应该是"用默认值继续跑"，而不是"整个知识库不能检索"。
     *
     * @param base         全局默认（通常来自 {@link #fromGlobal(RagProperties)}），不可为 null
     * @param overrideJson 库级覆盖 JSON（{@code knowledge_base.rag_config}），可为 null/空
     * @return 合并后的参数快照
     */
    public static RagConfig merge(RagConfig base, String overrideJson) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为空");
        }
        if (overrideJson == null || overrideJson.isBlank()) {
            return base;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(overrideJson);
        } catch (JsonProcessingException e) {
            LOG.warn("库级 rag_config 不是合法 JSON，本次回退全局默认: {}", e.getOriginalMessage());
            return base;
        }
        if (node == null || !node.isObject()) {
            LOG.warn("库级 rag_config 不是 JSON 对象，本次回退全局默认");
            return base;
        }
        rejectVectorDimOverride(node, base.vectorDim());
        return new RagConfig(
                text(node, KEY_EMBEDDING_MODEL, base.embeddingModel()),
                base.vectorDim(),
                integer(node, KEY_CHUNK_SIZE, base.chunkSize()),
                integer(node, KEY_CHUNK_OVERLAP, base.chunkOverlap()),
                integer(node, KEY_RETRIEVAL_TOP_K, base.retrievalTopK()),
                integer(node, KEY_FINAL_TOP_N, base.finalTopN()),
                integer(node, KEY_RRF_K, base.rrfK()),
                decimal(node, KEY_SCORE_THRESHOLD, base.scoreThreshold()),
                integer(node, KEY_CONTEXT_EXPAND, base.contextExpand()),
                bool(node, KEY_FTS_ENABLED, base.ftsEnabled()),
                text(node, KEY_FTS_TS_CONFIG, base.ftsTsConfig()));
    }

    /**
     * {@code vector_dim} 是唯一不接受库级覆盖的参数：它与 pgvector 列类型 {@code vector(1024)} 硬绑定，
     * 单个库偷偷改成别的维度，写入时会直接被数据库拒绝、或者写进去也永远检索不出来。
     * 所以这里只打 WARN 让人看见"你这条配置没生效"，然后照旧用全局维度。
     */
    private static void rejectVectorDimOverride(JsonNode node, int globalDim) {
        JsonNode value = pick(node, KEY_VECTOR_DIM);
        if (value == null || value.isNull()) {
            return;
        }
        LOG.warn("库级 rag_config 试图覆盖 vector_dim={}，已忽略：维度与 pgvector 列类型强绑定，"
                + "全局固定为 {}，换维度必须改列定义并全库重建", value.asText(), globalDim);
    }

    /**
     * 判断相对另一份参数，本份是否属于"建库参数变更"——变了就必须全库重建向量。
     *
     * <p>为什么只看这四项：模型/维度决定向量本身，切块大小/重叠决定切出来的块，
     * 四者任一变化，库里已存的向量与新查询就不在同一个语义空间（或不是同一批块），继续用等于乱找。
     * 检索参数（阈值/topK 等）只影响"怎么挑"，不影响"存了什么"，改了即时生效即可。
     *
     * @param other 对比的另一份参数，为 null 时视为不需要重建
     * @return true 表示需要全库重建
     */
    public boolean requiresRebuild(RagConfig other) {
        if (other == null) {
            return false;
        }
        boolean sameModel = embeddingModel == null
                ? other.embeddingModel() == null
                : embeddingModel.equals(other.embeddingModel());
        return !sameModel
                || vectorDim != other.vectorDim()
                || chunkSize != other.chunkSize()
                || chunkOverlap != other.chunkOverlap();
    }

    /** 取字符串值，缺失/为 null 时回退。 */
    private static String text(JsonNode node, String snakeKey, String fallback) {
        JsonNode value = pick(node, snakeKey);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String parsed = value.asText();
        return parsed == null || parsed.isBlank() ? fallback : parsed;
    }

    /** 取整数值，缺失/非数字时回退（不抛异常，见类注释的容错原则）。 */
    private static int integer(JsonNode node, String snakeKey, int fallback) {
        JsonNode value = pick(node, snakeKey);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            return fallback;
        }
        return value.asInt(fallback);
    }

    /** 取浮点值，缺失/非数字时回退。 */
    private static double decimal(JsonNode node, String snakeKey, double fallback) {
        JsonNode value = pick(node, snakeKey);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        return value.asDouble(fallback);
    }

    /** 取布尔值，缺失/非布尔时回退。 */
    private static boolean bool(JsonNode node, String snakeKey, boolean fallback) {
        JsonNode value = pick(node, snakeKey);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return fallback;
        }
        return value.asBoolean(fallback);
    }

    /** 先按 snake_case 找，找不到再按 camelCase 找（前端与文档两种写法都认）。 */
    private static JsonNode pick(JsonNode node, String snakeKey) {
        JsonNode value = node.get(snakeKey);
        if (value == null) {
            value = node.get(toCamel(snakeKey));
        }
        return value;
    }

    /** {@code chunk_size} → {@code chunkSize}。 */
    private static String toCamel(String snakeKey) {
        StringBuilder builder = new StringBuilder(snakeKey.length());
        boolean upperNext = false;
        for (int i = 0; i < snakeKey.length(); i++) {
            char ch = snakeKey.charAt(i);
            if (ch == UNDERSCORE) {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return builder.toString();
    }
}
