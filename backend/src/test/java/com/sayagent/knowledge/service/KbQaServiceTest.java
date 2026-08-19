package com.sayagent.knowledge.service;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.config.RagConfig;
import com.sayagent.knowledge.config.RagProperties;
import com.sayagent.knowledge.eval.EvalRunner;
import com.sayagent.knowledge.entity.KnowledgeBase;
import com.sayagent.knowledge.entity.RetrievalLog;
import com.sayagent.knowledge.repository.KnowledgeBaseRepository;
import com.sayagent.knowledge.repository.RetrievalLogRepository;
import com.sayagent.knowledge.retriever.RetrievalResult;
import com.sayagent.knowledge.web.AskResponse;
import com.sayagent.knowledge.web.EvalRequest;
import com.sayagent.knowledge.web.EvalResultVO;
import com.sayagent.knowledge.web.ProbeResultVO;
import com.sayagent.modelprovider.client.ProviderConfig;
import com.sayagent.modelprovider.route.ProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KbQaService（问答 / 试问台 / 题集打分）单测（K8）。
 *
 * <p>K5 的 {@link RagQueryService} 是 mock：本类只负责「编排」，拒答阈值、改写、溯源都是 K5 的职责，
 * 这里验的是「参数有没有正确组装、结果有没有正确映射成 VO、判权有没有生效」。不连真库（§7.10 规则35）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbQaServiceTest {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock RagQueryService ragQueryService;
    @Mock ProviderRouter providerRouter;
    @Mock RetrievalLogRepository retrievalLogRepository;
    @Mock EvalRunner evalRunner;
    @Mock KbRetrievalService kbRetrievalService;

    private KbQaService kbQaService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null, List.of()));
        RagProperties props = new RagProperties();
        kbQaService = new KbQaService(ragQueryService, providerRouter, retrievalLogRepository,
                new KbAccessGuard(kbRepository), evalRunner, kbRetrievalService);
        when(kbRepository.findById(anyLong())).thenReturn(Optional.of(kb(1L, "tester")));
        // KbRetrievalService 重构后遗留：probe/eval/ask 均走 kbRetrievalService（库级生效配置 + 检索）
        when(kbRetrievalService.effectiveConfig(any())).thenReturn(
                new RagConfig("bge-m3", 1024, 800, 120, 10, 4, 60, 0.6, 1, true, "zhparser_cfg"));
        when(kbRetrievalService.retrieve(anyLong(), anyString())).thenReturn(List.of());
        when(providerRouter.getDefaultChatConfig())
                .thenReturn(new ProviderConfig("http://x", "k", "m", 30000));
    }

    private KnowledgeBase kb(long id, String creator) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("kb");
        kb.setCreatorId(creator);
        return kb;
    }

    private RagQueryService.RagAnswer answered() {
        return new RagQueryService.RagAnswer(false, "答案 [来源1]", null,
                List.of(new RagQueryService.SourceRef(1, "doc-1", 0, "标题")), 0.87, 0.60, false);
    }

    // ===================== 问答 =====================

    @Test
    void ask_mapsRagAnswerToVo_withSources() {
        when(ragQueryService.query(any())).thenReturn(answered());

        AskResponse r = kbQaService.ask(1L, "问题", null);

        assertFalse(r.refused());
        assertEquals("答案 [来源1]", r.answer());
        assertEquals(1, r.sources().size());
        assertEquals("doc-1", r.sources().get(0).documentId());
        assertEquals(0.87, r.topScore());
    }

    @Test
    void ask_refusedByThreshold_carriesRefusalReasonName() {
        when(ragQueryService.query(any())).thenReturn(
                RagQueryService.RagAnswer.refuse(RetrievalLog.RefusalReason.BELOW_THRESHOLD, 0.6));

        AskResponse r = kbQaService.ask(1L, "问题", null);

        assertTrue(r.refused());
        assertEquals("BELOW_THRESHOLD", r.refusalReason());
        assertTrue(r.sources().isEmpty());
    }

    @Test
    void ask_passesKbIdAsBothMountedScopeAndPrimaryLogKb() {
        when(ragQueryService.query(any())).thenReturn(answered());

        kbQaService.ask(7L, "问题", null);

        verify(ragQueryService).query(org.mockito.ArgumentMatchers.argThat(req ->
                req.mountedKbIds().equals(List.of(7L))
                        && Long.valueOf(7L).equals(req.primaryKbId())
                        && req.providerConfig() != null
                        && req.ragConfig() != null));
    }

    @Test
    void ask_notCreatorNorAdmin_throwsForbidden() {
        when(kbRepository.findById(anyLong())).thenReturn(Optional.of(kb(1L, "someone-else")));

        BizException ex = assertThrows(BizException.class, () -> kbQaService.ask(1L, "q", null));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void ask_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(anyLong())).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> kbQaService.ask(404L, "q", null));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    // ===================== 试问台 =====================

    @Test
    void probe_aboveThreshold_hitTrue_andNeverCallsLlm() {
        when(kbRetrievalService.retrieve(anyLong(), anyString()))
                .thenReturn(List.of(result("doc-1", 0, "片段内容", 0.91)));

        ProbeResultVO r = kbQaService.probe(1L, "问题");

        assertTrue(r.hit());
        assertEquals(0.91, r.topScore());
        assertEquals(1, r.candidates().size());
        verify(ragQueryService, times(0)).query(any());
    }

    @Test
    void probe_noHit_returnsEmptyCandidatesAndHitFalse() {
        when(kbRetrievalService.retrieve(anyLong(), anyString())).thenReturn(List.of());

        ProbeResultVO r = kbQaService.probe(1L, "问题");

        assertFalse(r.hit());
        assertEquals(0.0, r.topScore());
        assertTrue(r.candidates().isEmpty());
    }

    @Test
    void probe_capsCandidatesAtFive() {
        List<RetrievalResult> many = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(result("doc-" + i, i, "c" + i, 0.9 - i * 0.01));
        }
        when(kbRetrievalService.retrieve(anyLong(), anyString())).thenReturn(many);

        ProbeResultVO r = kbQaService.probe(1L, "问题");

        assertEquals(5, r.candidates().size());
    }

    @Test
    void probe_longContent_truncatedToSnippet() {
        String long300 = "字".repeat(300);
        when(kbRetrievalService.retrieve(anyLong(), anyString()))
                .thenReturn(List.of(result("doc-1", 0, long300, 0.9)));

        ProbeResultVO r = kbQaService.probe(1L, "问题");

        assertEquals(201, r.candidates().get(0).snippet().length());
        assertTrue(r.candidates().get(0).snippet().endsWith("…"));
    }

    // ===================== 题集打分 =====================

    @Test
    void eval_mixesAnsweredAndRefused_computesHitRate() {
        when(ragQueryService.query(any()))
                .thenReturn(answered())
                .thenReturn(RagQueryService.RagAnswer.refuse(RetrievalLog.RefusalReason.BELOW_THRESHOLD, 0.6));
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of());

        EvalResultVO r = kbQaService.eval(1L, List.of(
                new EvalRequest.EvalQuestion("q1", null, null),
                new EvalRequest.EvalQuestion("q2", null, true)));

        assertEquals(2, r.total());
        assertEquals(1, r.answered());
        assertEquals(1, r.refused());
        assertEquals(0.5, r.hitRate(), 1e-9);
        assertEquals(2, r.items().size());
    }

    @Test
    void eval_faithfulnessMarkedNotAvailableUntilEvalModelLands() {
        when(ragQueryService.query(any())).thenReturn(answered());
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of());

        EvalResultVO r = kbQaService.eval(1L, List.of(new EvalRequest.EvalQuestion("q", null, null)));

        assertEquals("N/A（需评测模型）", r.faithfulness());
    }

    @Test
    void eval_avgCostMsFromRetrievalLogs() {
        when(ragQueryService.query(any())).thenReturn(answered());
        RetrievalLog a = new RetrievalLog();
        a.setCostMs(100L);
        RetrievalLog b = new RetrievalLog();
        b.setCostMs(300L);
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of(a, b));

        EvalResultVO r = kbQaService.eval(1L, List.of(new EvalRequest.EvalQuestion("q", null, null)));

        assertEquals(200.0, r.avgCostMs(), 1e-9);
    }

    @Test
    void eval_emptyQuestions_returnsZeroedMetricsWithoutCallingLlm() {
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of());

        EvalResultVO r = kbQaService.eval(1L, List.of());

        assertEquals(0, r.total());
        assertEquals(0.0, r.hitRate());
        assertEquals(0.0, r.avgTopScore());
        verify(ragQueryService, times(0)).query(any());
    }

    @Test
    void runFullEval_delegatesToEvalRunner_andReturnsReport() {
        EvalRunner.EvalReport report = EvalRunner.EvalReport.empty();
        when(evalRunner.run(1L)).thenReturn(report);

        EvalRunner.EvalReport r = kbQaService.runFullEval(1L);

        assertSame(report, r);
        verify(evalRunner).run(1L);
    }

    private RetrievalResult result(String docId, int idx, String content, double score) {
        return new RetrievalResult(docId, idx, content, score, idx + 1,
                RetrievalResult.RetrievalSource.SEMANTIC, score);
    }

    /** 让 BigDecimal 引用不被优化掉（体检口径与本类共用 RetrievalLog 字段）。 */
    @SuppressWarnings("unused")
    private static final BigDecimal UNUSED = BigDecimal.ZERO;
}
