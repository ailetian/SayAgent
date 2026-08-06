package com.hify.hify.knowledge.eval;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.config.RagProperties;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.EvalDatasetRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.knowledge.service.KbAccessGuard;
import com.hify.hify.knowledge.service.QueryRewriter;
import com.hify.hify.knowledge.service.RagQueryService;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.route.ProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EvalRunner 编排单测（K10）。纯 Mockito，不连真库 / 不调真 LLM（§7.10 规则35）。
 * 直接注入题集调用 {@code run(kbId, cases)}，绕开资源加载路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvalRunnerTest {

    @Mock RagQueryService ragQueryService;
    @Mock RetrievalPort retrievalPort;
    @Mock QueryRewriter queryRewriter;
    @Mock ProviderRouter providerRouter;
    @Mock EvalDatasetRepository evalDatasetRepository;
    @Mock KbAccessGuard accessGuard;

    private EvalRunner runner;

    @BeforeEach
    void setUp() {
        runner = new EvalRunner(ragQueryService, retrievalPort, queryRewriter,
                providerRouter, evalDatasetRepository, accessGuard, new RagProperties());

        KnowledgeBase kb = org.mockito.Mockito.mock(KnowledgeBase.class);
        when(kb.getEffectiveConfig(any(RagConfig.class)))
                .thenReturn(new RagConfig("bge-m3", 1024, 200, 20, 10, 5, 60, 0.6, 1, true, "zhparser_cfg"));
        when(accessGuard.requireAccessible(anyLong())).thenReturn(kb);
        when(queryRewriter.rewrite(anyString(), any())).thenReturn("q");
        when(evalDatasetRepository.findByKbId(anyLong())).thenReturn(List.of());
    }

    private RagQueryService.RagAnswer answered() {
        return new RagQueryService.RagAnswer(false, "年假 5 天 [来源1]", null,
                List.of(new RagQueryService.SourceRef(1, "doc-1", 0, "年假条款")), 0.87, 0.60);
    }

    private RagQueryService.RagAnswer refused() {
        return RagQueryService.RagAnswer.refuse(
                com.hify.hify.knowledge.entity.RetrievalLog.RefusalReason.BELOW_THRESHOLD, 0.60);
    }

    private List<RetrievalResult> relevantChunks() {
        return List.of(
                new RetrievalResult("doc-1", 0, "正式员工每年享有 5 天年假，带薪。", 0.90, 1,
                        RetrievalResult.RetrievalSource.SEMANTIC, 0.90),
                new RetrievalResult("doc-1", 1, "婚假为 3 天，陪产假 15 天。", 0.88, 2,
                        RetrievalResult.RetrievalSource.SEMANTIC, 0.88),
                new RetrievalResult("doc-1", 2, "请假需主管审批。", 0.80, 3,
                        RetrievalResult.RetrievalSource.FTS, 0.0));
    }

    private LlmResponse judgeJson() {
        return new LlmResponse("{\"faithfulness\":0.9,\"answer_relevancy\":0.85}", null, null, null, null);
    }

    @Test
    void run_normalCases_computesRecallAndFaithfulness_andPassesGate() {
        when(ragQueryService.query(any())).thenReturn(answered());
        when(retrievalPort.retrieveHybrid(anyString(), anyList(), any())).thenReturn(relevantChunks());
        when(providerRouter.route(any())).thenReturn(judgeJson());

        EvalRunner.EvalReport r = runner.run(1L, List.of(
                new EvalRunner.EvalCase("正式员工年假有几天？", "事实", "年假,天数", "5 天", false),
                new EvalRunner.EvalCase("婚假和陪产假分别多久？", "综合", "婚假,3天", "3 天/15 天", false)));

        assertEquals(2, r.total());
        assertEquals(2, r.answered());
        assertEquals(0, r.refused());
        assertEquals(1.0, r.recallAt5Mean(), 1e-9);
        assertEquals(0.9, r.faithfulnessMean(), 1e-9);
        assertEquals(0.85, r.answerRelevancyMean(), 1e-9);
        assertTrue(r.gate());
    }

    @Test
    void run_shouldRejectCase_refusedCountsAsRejectionAccuracy() {
        when(ragQueryService.query(any())).thenAnswer(inv -> {
            com.hify.hify.knowledge.service.RagQueryService.RagQueryRequest req = inv.getArgument(0);
            return req.query().contains("诗") ? refused() : answered();
        });
        when(retrievalPort.retrieveHybrid(anyString(), anyList(), any())).thenReturn(relevantChunks());
        when(providerRouter.route(any())).thenReturn(judgeJson());

        EvalRunner.EvalReport r = runner.run(1L, List.of(
                new EvalRunner.EvalCase("帮我写一首诗", "拒答", "", "", true),
                new EvalRunner.EvalCase("正式员工年假有几天？", "事实", "年假,天数", "5 天", false)));

        assertEquals(2, r.total());
        assertEquals(1, r.refused());
        assertEquals(1, r.answered());
        assertEquals(1.0, r.rejectionAccuracy(), 1e-9);
        assertEquals(0.0, r.wrongRefusalRate(), 1e-9);
        assertTrue(r.gate());
    }

    @Test
    void run_inScopeCaseWronglyRefused_lowersGate() {
        when(ragQueryService.query(any())).thenReturn(refused());
        when(retrievalPort.retrieveHybrid(anyString(), anyList(), any())).thenReturn(relevantChunks());

        EvalRunner.EvalReport r = runner.run(1L, List.of(
                new EvalRunner.EvalCase("正式员工年假有几天？", "事实", "年假,天数", "5 天", false)));

        assertEquals(1, r.refused());
        assertEquals(1.0, r.wrongRefusalRate(), 1e-9);
        assertFalse(r.gate());
    }

    @Test
    void run_emptyCases_returnsEmptyReport() {
        EvalRunner.EvalReport r = runner.run(1L, List.of());
        assertEquals(0, r.total());
        assertFalse(r.gate());
    }
}
