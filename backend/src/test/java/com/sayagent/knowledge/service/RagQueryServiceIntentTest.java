package com.sayagent.knowledge.service;

import com.sayagent.knowledge.config.RagConfig;
import com.sayagent.knowledge.entity.Document;
import com.sayagent.knowledge.entity.RetrievalLog;
import com.sayagent.knowledge.repository.DocumentChunkRepository;
import com.sayagent.knowledge.repository.DocumentRepository;
import com.sayagent.knowledge.repository.RetrievalLogRepository;
import com.sayagent.knowledge.retriever.RetrievalResult;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.client.ProviderConfig;
import com.sayagent.modelprovider.route.ProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K0808 T3 单测：意图网关接入 query() 的分支验证。
 *
 * <p>覆盖：GREETING / IDENTITY / MEANINGLESS / TOOL 四类被拦截返回 canned（不检索、不写 RetrievalLog），
 * 以及 QUESTION（"你好，年假怎么请"）不被误杀、正常走检索给出带 [来源i] 的答案。
 *
 * <p>QueryRewriter / QueryIntentClassifier 用真实实现（验证网关端到端），其余重依赖全部 mock。
 */
@ExtendWith(MockitoExtension.class)
class RagQueryServiceIntentTest {

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

    @BeforeEach
    void init() {
        ragQueryService = new RagQueryService(new QueryRewriter(),
                documentChunkRepository, documentRepository, providerRouter, retrievalLogRepository,
                kbRetrievalService, new QueryIntentClassifier(new QueryRewriter()));
    }

    private RagConfig ragConfig() {
        return new RagConfig("bge-m3", 1024, 800, 120, 10, 4, 60, 0.6, 1, true, "zhparser_cfg");
    }

    private RagQueryService.RagQueryRequest req(String query) {
        return new RagQueryService.RagQueryRequest(
                query, null, List.of(1L), 99L, ragConfig(), new ProviderConfig(), 1L);
    }

    // ---------- 4 类拦截：返回 canned，不检索、不写 RetrievalLog ----------

    @Test
    void greeting_returnsCanned_andNoRetrievalOrLog() {
        RagQueryService.RagAnswer ans = ragQueryService.query(req("你好"));

        assertTrue(ans.intentFiltered(), "意图网关拦截应标记 intentFiltered=true");
        assertFalse(ans.refused(), "canned 不是知识库拒答");
        assertEquals("你好！我是你的企业知识库助手，有什么可以帮你的吗？", ans.answer());
        verify(kbRetrievalService, never()).retrieve(any(), any(), any());
        verify(retrievalLogRepository, never()).save(any());
    }

    @Test
    void identity_returnsCanned_andNoRetrievalOrLog() {
        RagQueryService.RagAnswer ans = ragQueryService.query(req("你是谁"));

        assertTrue(ans.intentFiltered());
        assertEquals("我是 SayAgent 企业知识库问答助手，可以基于你挂载的知识库回答相关问题。", ans.answer());
        verify(kbRetrievalService, never()).retrieve(any(), any(), any());
        verify(retrievalLogRepository, never()).save(any());
    }

    @Test
    void meaningless_returnsCanned_andNoRetrievalOrLog() {
        RagQueryService.RagAnswer ans = ragQueryService.query(req("？？？"));

        assertTrue(ans.intentFiltered());
        assertEquals("抱歉，我没太理解你的问题，可以换种说法再问我吗？", ans.answer());
        verify(kbRetrievalService, never()).retrieve(any(), any(), any());
        verify(retrievalLogRepository, never()).save(any());
    }

    @Test
    void tool_returnsUnsupportedCanned_andNoRetrievalOrLog() {
        RagQueryService.RagAnswer ans = ragQueryService.query(req("帮我发邮件给张三"));

        assertTrue(ans.intentFiltered());
        assertEquals("当前暂不支持该操作，我可以帮你检索知识库内容，请直接提问。", ans.answer());
        verify(kbRetrievalService, never()).retrieve(any(), any(), any());
        verify(retrievalLogRepository, never()).save(any());
    }

    @Test
    void thanksAndYouThere_alsoIntercepted() {
        assertFalse(ragQueryService.query(req("谢谢")).refused());
        assertTrue(ragQueryService.query(req("谢谢")).intentFiltered());
        assertTrue(ragQueryService.query(req("在吗")).intentFiltered());
    }

    // ---------- 1 类不误杀：真实问题正常检索 ----------

    @Test
    void question_withGreetingPrefix_notKilled_proceedsToRetrieve() {
        // "你好，年假怎么请" 清洗后剩「年假怎么请」→ QUESTION，不得被网关拦截
        RetrievalResult hit = new RetrievalResult("doc-1", 5, "命中块内容", 0.8, 1,
                RetrievalResult.RetrievalSource.SEMANTIC, 0.8);
        when(kbRetrievalService.retrieve(any(), any(), any())).thenReturn(List.of(hit));

        DocumentChunkRepository.DocumentChunk self =
                new DocumentChunkRepository.DocumentChunk("doc-1", 1L, 5, "命中块内容", null);
        when(documentChunkRepository.findByDocumentIdAndSeqBetween(eq("doc-1"), eq(4), eq(6)))
                .thenReturn(List.of(self));

        Document doc = new Document();
        doc.setDocumentId("doc-1");
        doc.setTitle("员工手册");
        when(documentRepository.findByDocumentId("doc-1")).thenReturn(Optional.of(doc));

        when(providerRouter.route(any()))
                .thenReturn(LlmResponse.builder().content("年假为5天[来源1]").finishReason("stop").build());

        RagQueryService.RagAnswer ans = ragQueryService.query(req("你好，年假怎么请"));

        assertFalse(ans.intentFiltered(), "真实问题不应被意图网关拦截");
        assertFalse(ans.refused());
        assertTrue(ans.answer().contains("[来源1]"), "应正常检索并带来源标记");
        verify(kbRetrievalService).retrieve(any(), any(), any());
        verify(retrievalLogRepository).save(any(RetrievalLog.class));
    }
}
