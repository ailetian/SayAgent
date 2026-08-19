package com.sayagent.knowledge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EmbeddingConfig 单元测试（M5 T2）：验证阈值/维度/批大小能从 hify.embedding 配置正确读取。
 * 不加载 Spring 上下文，用 Binder 直接绑定 Map 配置（离线、可重复）。
 */
class EmbeddingConfigTest {

    @Test
    void testBind_fromProperties_readsDimensionThresholdAndBatchSize() {
        Map<String, Object> props = new HashMap<>();
        props.put("hify.embedding.dimension", "1024");
        props.put("hify.embedding.similarity-threshold", "0.6");
        props.put("hify.embedding.max-chunk-size", "1000");
        props.put("hify.embedding.batch-size", "20");

        MapConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        EmbeddingConfig config = new Binder(source)
                .bind("hify.embedding", Bindable.of(EmbeddingConfig.class))
                .get();

        assertEquals(1024, config.getDimension());
        assertEquals(0.6, config.getSimilarityThreshold(), 0.0001);
        assertEquals(1000, config.getMaxChunkSize());
        assertEquals(20, config.getBatchSize());
    }

    @Test
    void testDefaults_whenPropertiesAbsent_usesSafeDefaults() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(new HashMap<>());
        EmbeddingConfig config = new Binder(source)
                .bind("hify.embedding", Bindable.of(EmbeddingConfig.class))
                .orElseGet(EmbeddingConfig::new);

        assertEquals(1024, config.getDimension());
        assertEquals(0.6, config.getSimilarityThreshold(), 0.0001);
        assertEquals(1000, config.getMaxChunkSize());
        assertEquals(20, config.getBatchSize());
    }
}
