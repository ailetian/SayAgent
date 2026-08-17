package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.EmbeddingConfig;
import com.hify.hify.modelprovider.client.ProviderClient;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.route.ProviderRouter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 服务（M5 T2）。只依赖 M3 的 {@link ProviderClient#embed} 接口，不直接感知具体厂商（§3.3 解耦）。
 *
 * <p>大白话：把若干长文本先按 maxChunkSize 切成切片，再把切片按 batchSize 累积成批，
 * 每满一批调一次 ProviderClient.embed(batch) 拿向量；返回向量列表，顺序与切片一一对应。
 * 空输入直接返回空列表，绝不调 embed（T2 验收）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final ProviderRouter providerRouter;
    private final EmbeddingConfig embeddingConfig;

    /**
     * 切块 + 批量向量化（核心入口）。
     *
     * @param texts 待向量化文本（可含长文本）；null/空 → 返回空列表，不调 embed
     * @return 与切片顺序一一对应的向量列表，长度等于切片数
     */
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> slices = splitIntoChunks(texts);
        if (slices.isEmpty()) {
            return List.of();
        }

        ProviderClient client = providerRouter.getEmbeddingClient();
        ProviderConfig config = providerRouter.getEmbeddingConfig();
        int batchSize = embeddingConfig.getBatchSize();
        int dim = embeddingConfig.getDimension();

        List<float[]> result = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i += batchSize) {
            int end = Math.min(i + batchSize, slices.size());
            List<String> batch = slices.subList(i, end);
            List<float[]> embeddings = safeEmbed(batch, client, config);
            log.info("embedding batch size={} vectors={} expectedDim={}", batch.size(), embeddings.size(), dim);
            for (float[] v : embeddings) {
                if (v == null || v.length != dim) {
                    throw new BizException(ErrorCode.EMBEDDING_FAILED,
                            "向量维度不符：期望 " + dim + "，实际 " + (v == null ? "null" : v.length));
                }
                result.add(v);
            }
        }
        return result;
    }

    /**
     * 对已切好的切片直接批量向量化（不重复切片，消除 indexDocument 的重复切块）。
     *
     * @param slices 已切分的文本切片（顺序即返回向量的顺序）；null/空 → 返回空列表
     * @return 与切片顺序一一对应的向量列表
     */
    public List<float[]> embedSlices(List<String> slices) {
        if (slices == null || slices.isEmpty()) {
            return List.of();
        }
        ProviderClient client = providerRouter.getEmbeddingClient();
        ProviderConfig config = providerRouter.getEmbeddingConfig();
        int batchSize = embeddingConfig.getBatchSize();
        int dim = embeddingConfig.getDimension();

        List<float[]> result = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i += batchSize) {
            int end = Math.min(i + batchSize, slices.size());
            List<String> batch = slices.subList(i, end);
            List<float[]> embeddings = safeEmbed(batch, client, config);
            log.info("embedding batch size={} vectors={} expectedDim={}", batch.size(), embeddings.size(), dim);
            for (float[] v : embeddings) {
                if (v == null || v.length != dim) {
                    throw new BizException(ErrorCode.EMBEDDING_FAILED,
                            "向量维度不符：期望 " + dim + "，实际 " + (v == null ? "null" : v.length));
                }
                result.add(v);
            }
        }
        return result;
    }

    /**
     * 安全调用底层 embed：把供应商网络/协议层异常（例如库里只有 LLM 供应商、embedding 退化到
     * 不支持 embedding 的厂商而导致的 404）统一翻译成 {@link ErrorCode#EMBEDDING_FAILED} 友好提示，
     * 避免裸抛底层异常被 {@code GlobalExceptionHandler} 兜底成笼统的「系统错误」。
     * 已经语义化的 {@link BizException} 直接透传，不重复包裹（避免提示堆叠）。
     */
    private List<float[]> safeEmbed(List<String> batch, ProviderClient client, ProviderConfig config) {
        try {
            return client.embed(batch, config);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("embedding call failed, check Embedding provider config (e.g. local Ollama + BGE-M3)", e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED,
                    "向量化调用失败：请检查 Embedding 模型供应商配置（需配置支持 embedding 的供应商，"
                            + "如本地 Ollama + BGE-M3，且地址/密钥正确）");
        }
    }

    /**
     * 把若干文本按 maxChunkSize 切成切片（供调用方需要“切片文本 + 向量”成对落库时用）。
     * 空文本、null 文本会被跳过；全部为空则返回空列表。
     */
    public List<String> splitIntoChunks(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int maxChunkSize = embeddingConfig.getMaxChunkSize();
        List<String> slices = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            slices.addAll(split(text, maxChunkSize));
        }
        return slices;
    }

    /** 按 maxChunkSize 贪心切片（纯长度切，不做语义断句）。 */
    private List<String> split(String text, int maxChunkSize) {
        List<String> slices = new ArrayList<>();
        if (text.length() <= maxChunkSize) {
            slices.add(text);
            return slices;
        }
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + maxChunkSize, text.length());
            slices.add(text.substring(index, end));
            index = end;
        }
        return slices;
    }
}
