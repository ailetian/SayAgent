package com.hify.hify.knowledge.config;

import com.hify.hify.knowledge.entity.KnowledgeBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * K2 单元测试：RAG 三层参数合并（全局默认 + 库级覆盖）与 zhparser 探针降级。
 *
 * <p>不连真实库、不起 Spring 上下文（§7.10 规则 35）：配置绑定用 {@code Binder} 直接读，
 * PG 探测用 Mockito mock 掉 {@code pgJdbcTemplate}。
 */
@ExtendWith(MockitoExtension.class)
class RagConfigTest {

    private static final double DELTA = 0.0001;

    /** 期望的出厂默认值，与需求 §7 / K2 文档逐项对齐（写死在测试里才能挡住"偷偷改默认值"）。 */
    private static final String EXPECTED_EMBEDDING_MODEL = "bge-m3";
    private static final int EXPECTED_VECTOR_DIM = 1024;
    private static final int EXPECTED_CHUNK_SIZE = 800;
    private static final int EXPECTED_CHUNK_OVERLAP = 120;
    private static final int EXPECTED_RETRIEVAL_TOP_K = 10;
    private static final int EXPECTED_FINAL_TOP_N = 4;
    private static final int EXPECTED_RRF_K = 60;
    private static final double EXPECTED_SCORE_THRESHOLD = 0.6;
    private static final int EXPECTED_CONTEXT_EXPAND = 1;

    @Mock
    private JdbcTemplate pgJdbcTemplate;

    private RagProperties properties;

    private RagConfig global;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        global = RagConfig.fromGlobal(properties);
    }

    @Test
    void testFromGlobal_noOverride_returnsFactoryDefaults() {
        assertEquals(EXPECTED_EMBEDDING_MODEL, global.embeddingModel());
        assertEquals(EXPECTED_VECTOR_DIM, global.vectorDim());
        assertEquals(EXPECTED_CHUNK_SIZE, global.chunkSize());
        assertEquals(EXPECTED_CHUNK_OVERLAP, global.chunkOverlap());
        assertEquals(EXPECTED_RETRIEVAL_TOP_K, global.retrievalTopK());
        assertEquals(EXPECTED_FINAL_TOP_N, global.finalTopN());
        assertEquals(EXPECTED_RRF_K, global.rrfK());
        assertEquals(EXPECTED_SCORE_THRESHOLD, global.scoreThreshold(), DELTA);
        assertEquals(EXPECTED_CONTEXT_EXPAND, global.contextExpand());
        assertTrue(global.ftsEnabled());
        assertEquals(RagProperties.TS_CONFIG_ZHPARSER, global.ftsTsConfig());
    }

    @Test
    void testBind_applicationYmlRagSection_readsRequiredDefaults() throws IOException {
        RagProperties bound = new Binder(ConfigurationPropertySources.from(loadApplicationYml()))
                .bind("rag", Bindable.of(RagProperties.class))
                .get();

        assertEquals(EXPECTED_EMBEDDING_MODEL, bound.getEmbeddingModel());
        assertEquals(EXPECTED_VECTOR_DIM, bound.getVectorDim());
        assertEquals(EXPECTED_CHUNK_SIZE, bound.getChunkSize());
        assertEquals(EXPECTED_CHUNK_OVERLAP, bound.getChunkOverlap());
        assertEquals(EXPECTED_RETRIEVAL_TOP_K, bound.getRetrievalTopK());
        assertEquals(EXPECTED_FINAL_TOP_N, bound.getFinalTopN());
        assertEquals(EXPECTED_RRF_K, bound.getRrfK());
        assertEquals(EXPECTED_SCORE_THRESHOLD, bound.getScoreThreshold(), DELTA);
        assertEquals(EXPECTED_CONTEXT_EXPAND, bound.getContextExpand());
        assertTrue(bound.getFts().isEnabled());
        assertEquals(RagProperties.TS_CONFIG_ZHPARSER, bound.getFts().getTsConfig());
    }

    @Test
    void testBind_ymlScoreThreshold_matchesEmbeddingSimilarityThreshold() throws IOException {
        Binder binder = new Binder(ConfigurationPropertySources.from(loadApplicationYml()));

        RagProperties rag = binder.bind("rag", Bindable.of(RagProperties.class)).get();
        EmbeddingConfig embedding = binder.bind("sayagent.embedding", Bindable.of(EmbeddingConfig.class)).get();

        assertEquals(embedding.getSimilarityThreshold(), rag.getScoreThreshold(), DELTA);
        assertEquals(embedding.getDimension(), rag.getVectorDim());
    }

    @Test
    void testGetEffectiveConfig_kbRagConfigBlank_fallsBackToGlobal() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setRagConfig(null);

        RagConfig effective = kb.getEffectiveConfig(global);

        assertEquals(global, effective);
    }

    @Test
    void testGetEffectiveConfig_kbOverridesTwoFields_othersStayGlobal() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setRagConfig("{\"chunk_size\":300,\"score_threshold\":0.75}");

        RagConfig effective = kb.getEffectiveConfig(global);

        assertEquals(300, effective.chunkSize());
        assertEquals(0.75, effective.scoreThreshold(), DELTA);
        assertEquals(EXPECTED_CHUNK_OVERLAP, effective.chunkOverlap());
        assertEquals(EXPECTED_FINAL_TOP_N, effective.finalTopN());
        assertEquals(EXPECTED_EMBEDDING_MODEL, effective.embeddingModel());
    }

    @Test
    void testMerge_camelCaseKeys_appliesOverride() {
        RagConfig effective = RagConfig.merge(global, "{\"finalTopN\":8,\"ftsTsConfig\":\"simple\"}");

        assertEquals(8, effective.finalTopN());
        assertEquals(RagProperties.TS_CONFIG_SIMPLE, effective.ftsTsConfig());
        assertEquals(EXPECTED_CHUNK_SIZE, effective.chunkSize());
    }

    @Test
    void testMerge_invalidJson_returnsGlobalWithoutThrowing() {
        RagConfig effective = assertDoesNotThrow(() -> RagConfig.merge(global, "{chunk_size: broken"));

        assertEquals(global, effective);
    }

    @Test
    void testMerge_wrongValueType_fallsBackForThatFieldOnly() {
        RagConfig effective = RagConfig.merge(global, "{\"chunk_size\":\"eight-hundred\",\"final_top_n\":6}");

        assertEquals(EXPECTED_CHUNK_SIZE, effective.chunkSize());
        assertEquals(6, effective.finalTopN());
    }

    /** vector_dim 与 pgvector 列类型 vector(1024) 硬绑定，库级写了也必须被忽略，否则整库检索不出来。 */
    @Test
    void testMerge_vectorDimOverride_isIgnoredAndStaysGlobal() {
        RagConfig effective = RagConfig.merge(global, "{\"vector_dim\":768,\"chunk_size\":400}");

        assertEquals(EXPECTED_VECTOR_DIM, effective.vectorDim());
        assertEquals(400, effective.chunkSize());
    }

    /** camelCase 写法同样不能绕过 vector_dim 的锁定。 */
    @Test
    void testMerge_vectorDimCamelCaseOverride_isAlsoIgnored() {
        RagConfig effective = RagConfig.merge(global, "{\"vectorDim\":512}");

        assertEquals(EXPECTED_VECTOR_DIM, effective.vectorDim());
    }

    @Test
    void testRequiresRebuild_chunkSizeChanged_returnsTrue() {
        RagConfig changed = RagConfig.merge(global, "{\"chunk_size\":300}");

        assertTrue(changed.requiresRebuild(global));
    }

    @Test
    void testRequiresRebuild_onlyRetrievalParamsChanged_returnsFalse() {
        RagConfig changed = RagConfig.merge(global, "{\"score_threshold\":0.8,\"final_top_n\":9}");

        assertFalse(changed.requiresRebuild(global));
    }

    @Test
    void testProbe_zhparserInstalled_usesZhparserCfgAndKeepsFtsEnabled() {
        when(pgJdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        ZhparserProbe probe = new ZhparserProbe(pgJdbcTemplate, properties);

        boolean available = probe.probe();

        assertTrue(available);
        assertTrue(probe.isZhparserAvailable());
        assertEquals(RagProperties.TS_CONFIG_ZHPARSER, properties.getFts().getTsConfig());
        assertTrue(properties.getFts().isEnabled());
    }

    @Test
    void testProbe_zhparserMissing_downgradesToSimpleAndKeepsFtsEnabled() {
        when(pgJdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        ZhparserProbe probe = new ZhparserProbe(pgJdbcTemplate, properties);

        boolean available = probe.probe();

        assertFalse(available);
        assertFalse(probe.isZhparserAvailable());
        assertEquals(RagProperties.TS_CONFIG_SIMPLE, properties.getFts().getTsConfig());
        assertTrue(properties.getFts().isEnabled());
    }

    @Test
    void testProbe_queryThrows_doesNotBlockStartupAndDowngrades() {
        when(pgJdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("pg unreachable"));
        ZhparserProbe probe = new ZhparserProbe(pgJdbcTemplate, properties);

        boolean available = assertDoesNotThrow(probe::probe);

        assertFalse(available);
        assertEquals(RagProperties.TS_CONFIG_SIMPLE, properties.getFts().getTsConfig());
        assertTrue(properties.getFts().isEnabled());
    }

    @Test
    void testProbe_afterDowngrade_globalSnapshotReflectsSimple() {
        when(pgJdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        new ZhparserProbe(pgJdbcTemplate, properties).probe();

        RagConfig afterProbe = RagConfig.fromGlobal(properties);

        assertEquals(RagProperties.TS_CONFIG_SIMPLE, afterProbe.ftsTsConfig());
        assertTrue(afterProbe.ftsEnabled());
    }

    /** 从 classpath 真实加载 application.yml，验证 rag: 段确实存在且值正确（而非只测 Java 默认值）。 */
    private MutablePropertySources loadApplicationYml() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return sources;
    }
}
