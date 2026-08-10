package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.route.ProviderRouter;
import com.hify.hify.modelprovider.client.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K5 RagQueryService 单测（无 Maven，纯 Mockito 单测，不连真库 坑位2）。
 *
 * <p>覆盖验收点：拒答三型(NO_KB/NO_HIT/BELOW_THRESHOLD)不调 LLM、溯源答案含 [来源i]、
 * Small-to-Big 取邻块、检索日志每次写入含拒答分型+top_candidates、调 LLM 走 M3 ProviderClient。
 *
 * <p>注：意图网关（K0808 T2/T3）已接入 query() 顶端——本文件所有用例 query 均为真实问题
 * （"年假怎么请"等 classify→QUESTION），故照常走检索；意图拦截分支由 RagQueryServiceIntentTest 专测。
 */
@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ProviderRouter providerRouter;
    @Mock
    private RetrievalLogRepository retrievalLogRepository;
    @Mock
    private KbRetrievalService kbRetrievalService;

    private RagQueryService ragQueryService;

    @org.junit.jupiter.api.BeforeEach
    void init() {
        // 手动构造：QueryRewriter / QueryIntentClassifier 用真实实现（验证 R4 / 意图网关端到端），其余依赖全部 mock
        ragQueryService = new RagQueryService(new QueryRewriter(),
                documentChunkRepository, documentRepository, providerRouter, retrievalLogRepository,
                kbRetrievalService, new QueryIntentClassifier(new QueryRewriter()));
    }

    /** 默认 RagConfig：finalTopN=4, contextExpand=1, scoreThreshold=0.6。 */
    private RagConfig ragConfig() {
        return new RagConfig("bge-m3", 1024, 800, 120, 10, 4, 60, 0.6, 1, true, "zhparser_cfg");
    }

    private RagQueryService.RagQueryRequest req(List<Long> kbIds, List<ChatMessage> history) {
        return new RagQueryService.RagQueryRequest(
                "年假怎么请", history, kbIds, 99L, ragConfig(), new ProviderConfig(), null);
    }

    // ---------- 拒答型 ----------

    @Test
    void noMountedKb_returnsNoKb_andLogsNullKb() {
        RagQueryService.RagQueryRequest request = req(List.of(), List.of());

        RagQueryService.RagAnswer answer = ragQueryService.query(request);

        assertTrue(answer.refused());
        assertEquals(RetrievalLog.RefusalReason.NO_KB, answer.refusalReason());
        verify(kbRetrievalService, never()).retrieve(any(), any(), any());
        verify(providerRouter, never()).route(any());

        ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
        verify(retrievalLogRepository).save(captor.capture());
        RetrievalLog log = captor.getValue();
        assertEquals(RetrievalLog.RefusalReason.NO_KB, log.getRefusalReason());
        assertEquals(Boolean.TRUE, log.getRejected());
        // NO_KB 无挂载库 → kb_id 置空（V21 已放空）
        assertEquals(null, log.getKbId());
        assertEquals(99L, log.getAgentId());
    }

    @Test
    void retrieveEmpty_returnsNoHit() {
        when(kbRetrievalService.retrieve(any(), any(), any())).thenReturn(List.of());
        RagQueryService.RagQueryRequest request = req(List.of(1L), List.of());

        RagQueryService.RagAnswer answer = ragQueryService.query(request);

        assertTrue(answer.refused());
        assertEquals(RetrievalLog.RefusalReason.NO_HIT, answer.refusalReason());
        verify(providerRouter, never()).route(any());

        ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
        verify(retrievalLogRepository).save(captor.capture());
        assertEquals(RetrievalLog.RefusalReason.NO_HIT, captor.getValue().getRefusalReason());
        assertEquals(1L, captor.getValue().getKbId());
    }

    @Test
    void belowThreshold_returnsBelowThreshold_withoutLlm() {
        // FTS-only 命中：semanticScore=0.0 < 0.6 → 阈值拒答（不调 LLM）
        RetrievalResult hit = new RetrievalResult("doc-1", 3, "关键词命中但语义弱", 0.01, 1,
                RetrievalResult.RetrievalSource.FTS, 0.0);
        when(kbRetrievalService.retrieve(any(), any(), any())).thenReturn(List.of(hit));
        RagQueryService.RagQueryRequest request = req(List.of(1L), List.of());

        RagQueryService.RagAnswer answer = ragQueryService.query(request);

        assertTrue(answer.refused());
        assertEquals(RetrievalLog.RefusalReason.BELOW_THRESHOLD, answer.refusalReason());
        verify(providerRouter, never()).route(any());

        ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
        verify(retrievalLogRepository).save(captor.capture());
        RetrievalLog log = captor.getValue();
        assertEquals(RetrievalLog.RefusalReason.BELOW_THRESHOLD, log.getRefusalReason());
        assertNotNull(log.getTopCandidates(), "top_candidates 应记录候选供分析");
        assertTrue(log.getTopCandidates().contains("\"rank\":1"));
        assertEquals(0.0, log.getTopScore().doubleValue(), 1e-9);
        assertEquals(0.6, log.getThreshold().doubleValue(), 1e-9);
    }

    // ---------- 成功 + 溯源 + Small-to-Big ----------

    @Test
    void success_answerHasSource_andSmallToBigExpands() {
        // 语义命中：semanticScore=0.8 >= 0.6
        RetrievalResult hit = new RetrievalResult("doc-1", 5, "命中块内容", 0.8, 1,
                RetrievalResult.RetrievalSource.SEMANTIC, 0.8);
        when(kbRetrievalService.retrieve(any(), any(), any())).thenReturn(List.of(hit));

        // R5 Small-to-Big：取 seq 4..6
        DocumentChunkRepository.DocumentChunk before =
                new DocumentChunkRepository.DocumentChunk("doc-1", 1L, 4, "前一块内容", null);
        DocumentChunkRepository.DocumentChunk self =
                new DocumentChunkRepository.DocumentChunk("doc-1", 1L, 5, "命中块内容", null);
        DocumentChunkRepository.DocumentChunk after =
                new DocumentChunkRepository.DocumentChunk("doc-1", 1L, 6, "后一块内容", null);
        when(documentChunkRepository.findByDocumentIdAndSeqBetween(eq("doc-1"), eq(4), eq(6)))
                .thenReturn(List.of(before, self, after));

        Document doc = new Document();
        doc.setDocumentId("doc-1");
        doc.setTitle("员工手册");
        when(documentRepository.findByDocumentId("doc-1")).thenReturn(Optional.of(doc));

        when(providerRouter.route(any()))
                .thenReturn(LlmResponse.builder().content("年假为5天[来源1]").finishReason("stop").build());

        RagQueryService.RagQueryRequest request = req(List.of(1L), List.of());
        RagQueryService.RagAnswer answer = ragQueryService.query(request);

        assertFalse(answer.refused());
        assertTrue(answer.answer().contains("[来源1]"), "答案应带来源标记");
        assertEquals(1, answer.sources().size());
        assertEquals(5, answer.sources().get(0).seq());
        assertEquals("员工手册", answer.sources().get(0).title());
        verify(providerRouter, times(1)).route(any());
        verify(documentChunkRepository).findByDocumentIdAndSeqBetween(eq("doc-1"), eq(4), eq(6));

        ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
        verify(retrievalLogRepository).save(captor.capture());
        RetrievalLog log = captor.getValue();
        assertEquals(Boolean.FALSE, log.getRejected());
        assertNotNull(log.getHitChunks(), "成功也须记 hit_chunks");
        assertNotNull(log.getAnswer());
        assertEquals(0.8, log.getTopScore().doubleValue(), 1e-9);
    }

    @Test
    void llmCallFails_propagatesLlmCallFailed_andLogs() {
        RetrievalResult hit = new RetrievalResult("doc-1", 5, "命中块内容", 0.8, 1,
                RetrievalResult.RetrievalSource.SEMANTIC, 0.8);
        when(kbRetrievalService.retrieve(any(), any(), any())).thenReturn(List.of(hit));
        when(documentChunkRepository.findByDocumentIdAndSeqBetween(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(new DocumentChunkRepository.DocumentChunk("doc-1", 1L, 5, "命中块内容", null)));
        when(documentRepository.findByDocumentId("doc-1")).thenReturn(Optional.of(new Document()));
        when(providerRouter.route(any()))
                .thenThrow(new BizException(ErrorCode.LLM_CALL_FAILED));

        RagQueryService.RagQueryRequest request = req(List.of(1L), List.of());
        BizException ex = assertThrows(BizException.class, () -> ragQueryService.query(request));
        assertEquals(ErrorCode.LLM_CALL_FAILED, ex.getErrorCode());

        // LLM 失败仍记账（answer=null），不丢日志
        verify(retrievalLogRepository).save(any(RetrievalLog.class));
    }
}
