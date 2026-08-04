package com.hify.hify.knowledge.service;

import com.hify.hify.knowledge.config.EmbeddingConfig;
import com.hify.hify.modelprovider.client.ProviderClient;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.route.ProviderRouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingService 单元测试（M5 T2）：Mock ProviderClient，stub embed() 返回 List&lt;float[]&gt;；
 * 验证切块 + 按 batchSize 批量调用，以及空输入不调 embed。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private ProviderRouter providerRouter;
    @Mock
    private ProviderClient providerClient;

    private EmbeddingConfig embeddingConfig;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingConfig = new EmbeddingConfig();
        embeddingConfig.setDimension(1024);
        embeddingConfig.setSimilarityThreshold(0.6);
        embeddingConfig.setMaxChunkSize(20);
        embeddingConfig.setBatchSize(20);
        embeddingService = new EmbeddingService(providerRouter, embeddingConfig);

        // 部分用例提前返回不会用到这些 stub，用 lenient 避免严格桩校验报错
        lenient().when(providerRouter.getEmbeddingClient()).thenReturn(providerClient);
        lenient().when(providerRouter.getEmbeddingConfig())
                .thenReturn(ProviderConfig.builder().model("m").apiUrl("u").apiKey("k").build());
        // stub embed：返回与 batch 等长的向量列表，便于校验切片数 = 向量数
        lenient().when(providerClient.embed(any(), any())).thenAnswer(inv -> {
            List<String> batch = inv.getArgument(0);
            List<float[]> r = new ArrayList<>();
            for (String ignored : batch) {
                r.add(new float[1024]);
            }
            return r;
        });
    }

    @Test
    void testEmbedDocuments_emptyInput_returnsEmptyWithoutCallingEmbed() {
        List<float[]> result = embeddingService.embedDocuments(List.of());
        assertTrue(result.isEmpty());
        verify(providerClient, never()).embed(any(), any());
    }

    @Test
    void testEmbedDocuments_nullInput_returnsEmptyWithoutCallingEmbed() {
        List<float[]> result = embeddingService.embedDocuments(null);
        assertTrue(result.isEmpty());
        verify(providerClient, never()).embed(any(), any());
    }

    @Test
    void testEmbedDocuments_longText_splitByMaxChunkSizeThenSingleBatch() {
        // 一条 45 字文本，maxChunkSize=20 -> 3 个切片（20+20+5）；batchSize=20 -> 仅 1 次 embed 调用
        String longText = "a".repeat(45);
        List<float[]> result = embeddingService.embedDocuments(List.of(longText));

        assertEquals(3, result.size());
        verify(providerClient, times(1)).embed(any(), any());
    }

    @Test
    void testEmbedDocuments_manySlices_splitIntoBatchesByBatchSize() {
        // 45 条各 20 字文本 -> 45 个切片；batchSize=20 -> 3 批：20/20/5
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            texts.add("b".repeat(20));
        }
        List<float[]> result = embeddingService.embedDocuments(texts);

        assertEquals(45, result.size());
        verify(providerClient, times(3)).embed(any(), any());
    }

    @Test
    void testEmbedDocuments_emptySliceSkipped_noEmbedWhenAllBlank() {
        List<float[]> result = embeddingService.embedDocuments(Arrays.asList("", null, "   "));
        assertTrue(result.isEmpty());
        verify(providerClient, never()).embed(any(), any());
    }

    @Test
    void testSplitIntoChunks_returnsSlicesAlignedWithEmbed() {
        String text = "c".repeat(45);
        List<String> slices = embeddingService.splitIntoChunks(List.of(text));
        assertEquals(3, slices.size());
        for (String s : slices) {
            assertTrue(s.length() <= 20);
        }
    }
}
