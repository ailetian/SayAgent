package com.hify.hify.knowledge.web;

import com.hify.hify.common.Result;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.dto.MountRequest;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KnowledgeBase.ChunkStrategy;
import com.hify.hify.knowledge.entity.KnowledgeBase.Status;
import com.hify.hify.knowledge.service.KbAdminService;
import com.hify.hify.knowledge.service.IndexingJobService;
import com.hify.hify.knowledge.service.KbQaService;
import com.hify.hify.knowledge.service.KnowledgeService;
import com.hify.hify.knowledge.service.MountService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeController 控制层单测（M5/T5 + K8，纯 Mockito，无 Spring 上下文）。
 *
 * <p>大白话：硬纪律是「Controller 极薄——只收请求、调 Service、装 Result 盒子」。
 * 这里把 KnowledgeService / MountService / KbAdminService / KbQaService 全部替身，断言控制器只是把请求原样转交 service，并把结果装盒返回。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private MountService mountService;
    @Mock
    private KbAdminService kbAdminService;
    @Mock
    private KbQaService kbQaService;
    @Mock
    private IndexingJobService indexingJobService;

    @InjectMocks
    private KnowledgeController knowledgeController;

    /** KnowledgeBaseUploadRequest 构造顺序：kbId, type, filename, title, content, sourceUrl。 */
    private KnowledgeBaseUploadRequest uploadReq() {
        return new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "kb", "hello", null, null);
    }

    private KnowledgeBaseVO sampleVo() {
        return new KnowledgeBaseVO(1L, "kb", "", null, 1024, null, "me",
                ChunkStrategy.AUTO, "zh-CN", Status.ACTIVE, true, null);
    }

    // ===================== 原有 T5 用例（保留） =====================

    @Test
    void testUpload_serviceReturnsDocId_returnsDocumentVO() {
        KnowledgeBaseUploadRequest req = uploadReq();
        when(knowledgeService.uploadDocument(req)).thenReturn("doc-1");
        when(knowledgeService.getDocumentVO("doc-1"))
                .thenReturn(new DocumentVO("doc-1", "INDEXING", 0));

        Result<DocumentVO> result = knowledgeController.upload(req);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals("doc-1", result.getData().docId());
        assertEquals("INDEXING", result.getData().status());
    }

    @Test
    void testRetrieve_serviceReturnsChunks_returnsChunkVOList() {
        ChunkVO c1 = new ChunkVO(0.9, "content1", "doc-1", 0);
        ChunkVO c2 = new ChunkVO(0.8, "content2", "doc-1", 1);
        RetrieveRequest req = new RetrieveRequest("问题", 1L, 5);
        when(knowledgeService.retrieve(1L, "问题", 5)).thenReturn(List.of(c1, c2));

        Result<List<ChunkVO>> result = knowledgeController.retrieve(req);

        assertEquals(0, result.getCode());
        assertEquals(2, result.getData().size());
        assertEquals("content1", result.getData().get(0).content());
        assertEquals(0, result.getData().get(0).chunkIndex());
    }

    @Test
    void testRetrieve_topKNull_passesDefaultFiveToService() {
        RetrieveRequest req = new RetrieveRequest("问题", 1L, null);
        when(knowledgeService.retrieve(eq(1L), eq("问题"), eq(5))).thenReturn(List.of());

        knowledgeController.retrieve(req);

        verify(knowledgeService).retrieve(1L, "问题", 5);
    }

    @Test
    void testUpload_uploadDocumentAndGetDocumentVoBothCalled_delegates() {
        KnowledgeBaseUploadRequest req = uploadReq();
        when(knowledgeService.uploadDocument(req)).thenReturn("doc-9");
        when(knowledgeService.getDocumentVO("doc-9")).thenReturn(new DocumentVO("doc-9", "INDEXING", 0));

        knowledgeController.upload(req);

        verify(knowledgeService).uploadDocument(req);
        verify(knowledgeService).getDocumentVO("doc-9");
    }

    @Test
    void testRetrieve_kbIdAndQueryPassedToService_delegates() {
        RetrieveRequest req = new RetrieveRequest("q", 7L, 3);
        when(knowledgeService.retrieve(7L, "q", 3)).thenReturn(List.of());

        knowledgeController.retrieve(req);

        verify(knowledgeService).retrieve(7L, "q", 3);
    }

    // ===================== K8 接口层用例 =====================

    @Test
    void createBase_delegatesToService_returnsVO() {
        KnowledgeBaseCreateRequest req = new KnowledgeBaseCreateRequest("kb", "", null, null, null, null, null);
        when(kbAdminService.createBase(any())).thenReturn(sampleVo());

        Result<KnowledgeBaseVO> r = knowledgeController.createBase(req);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1L, r.getData().id());
        assertEquals("kb", r.getData().name());
    }

    @Test
    void listBases_delegatesToService_returnsPage() {
        when(kbAdminService.listBases(any(), anyInt()))
                .thenReturn(new PageVO<>(List.of(sampleVo()), null, false));

        Result<PageVO<KnowledgeBaseVO>> r = knowledgeController.listBases(null, 20);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1, r.getData().items().size());
        assertEquals(false, r.getData().hasMore());
    }

    @Test
    void uploadBatch_delegatesToService_returnsJobList() {
        List<KnowledgeBaseUploadItem> items = List.of(
                new KnowledgeBaseUploadItem(Document.SourceType.TEXT, null, "t1", "c1", null),
                new KnowledgeBaseUploadItem(Document.SourceType.TEXT, null, "t2", "c2", null));
        when(knowledgeService.uploadDocuments(anyList())).thenReturn(List.of("d1", "d2"));
        when(knowledgeService.getDocumentStatus("d1")).thenReturn("PROCESSING");
        when(knowledgeService.getDocumentStatus("d2")).thenReturn("PROCESSING");

        Result<UploadResponse> r = knowledgeController.uploadBatch(1L, items);

        assertEquals(0, r.getCode());
        assertEquals(2, r.getData().items().size());
        assertEquals("d1", r.getData().items().get(0).docId());
        verify(knowledgeService).uploadDocuments(anyList());
    }

    @Test
    void uploadBatch_moreThanTen_throwsUploadTooMany() {
        List<KnowledgeBaseUploadItem> items = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            items.add(new KnowledgeBaseUploadItem(Document.SourceType.TEXT, null, "t", "c", null));
        }

        BizException ex = assertThrows(BizException.class,
                () -> knowledgeController.uploadBatch(1L, items));

        assertEquals(ErrorCode.UPLOAD_TOO_MANY, ex.getErrorCode());
    }

    @Test
    void ask_delegatesToService_returnsAnswerWithSources() {
        AskRequest req = new AskRequest("q", null);
        AskResponse ar = new AskResponse(false, "ans", null, 0.8, 0.6,
                List.of(new AskResponse.AskSource(1, "d", 0, "t")));
        when(kbQaService.ask(eq(1L), eq("q"), any())).thenReturn(ar);

        Result<AskResponse> r = knowledgeController.ask(1L, req);

        assertEquals(0, r.getCode());
        assertEquals("ans", r.getData().answer());
        assertEquals(1, r.getData().sources().size());
        assertEquals(1, r.getData().sources().get(0).index());
    }

    @Test
    void health_delegatesToService_returnsThreeMetrics() {
        HealthVO hv = new HealthVO("HEALTHY", 1.0, 0.8, 100.0, 1, 1, 0, 5, 0.1);
        when(kbAdminService.health(1L)).thenReturn(hv);

        Result<HealthVO> r = knowledgeController.health(1L);

        assertEquals(0, r.getCode());
        assertEquals("HEALTHY", r.getData().basicHealth());
        assertEquals(0.8, r.getData().hitQuality());
    }

    @Test
    void probe_delegatesToService_returnsPreview() {
        ProbeResultVO pr = new ProbeResultVO(true, 0.9, 0.6,
                List.of(new ProbeResultVO.ProbeCandidate("d", 0, 0.9, "s")));
        when(kbQaService.probe(eq(1L), eq("q"))).thenReturn(pr);

        Result<ProbeResultVO> r = knowledgeController.probe(1L, new ProbeRequest("q"));

        assertEquals(0, r.getCode());
        assertTrue(r.getData().hit());
        assertEquals(0.9, r.getData().topScore());
    }

    @Test
    void eval_delegatesToService_returnsMetrics() {
        EvalRequest er = new EvalRequest(List.of(new EvalRequest.EvalQuestion("q", null, null)));
        EvalResultVO ev = new EvalResultVO(1, 1, 0, 1.0, 0.9, 100.0, "N/A（需评测模型）",
                List.of(new EvalResultVO.EvalItem("q", false, 0.9, 1, "a")));
        when(kbQaService.eval(eq(1L), anyList())).thenReturn(ev);

        Result<EvalResultVO> r = knowledgeController.eval(1L, er);

        assertEquals(0, r.getCode());
        assertEquals(1, r.getData().total());
        assertEquals(0, r.getData().refused());
    }

    @Test
    void listMounted_delegatesToMountService_returnsKbLinkVO() {
        when(mountService.getMountedKbIds(1L)).thenReturn(List.of(10L, 20L));

        Result<List<KbLinkVO>> r = knowledgeController.listMounted(1L);

        assertEquals(0, r.getCode());
        assertEquals(2, r.getData().size());
        assertEquals(10L, r.getData().get(0).kbId());
        assertEquals(20L, r.getData().get(1).kbId());
    }

    @Test
    void mount_delegatesToMountService_returnsBoolean() {
        when(mountService.mount(1L, 5L)).thenReturn(true);

        Result<Boolean> r = knowledgeController.mount(1L, new MountRequest(5L));

        assertEquals(0, r.getCode());
        assertTrue(r.getData());
    }

    @Test
    void unmount_delegatesToMountService() {
        Result<Void> r = knowledgeController.unmount(1L, 5L);

        assertEquals(0, r.getCode());
        verify(mountService).unmount(1L, 5L);
    }

    // ===================== K11 接口层用例（文档管理 + 索引任务态） =====================

    @Test
    void listDocuments_delegatesToService_returnsPage() {
        DocumentSummaryVO vo = new DocumentSummaryVO("doc-1", "t", "INDEXED", 3, 100L, "2026-08-05", 42L);
        when(knowledgeService.listDocuments(any(), any(), anyInt()))
                .thenReturn(new PageVO<>(List.of(vo), null, false));

        Result<PageVO<DocumentSummaryVO>> r = knowledgeController.listDocuments(1L, null, 20);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1, r.getData().items().size());
        assertEquals("doc-1", r.getData().items().get(0).docId());
        // jobId 必须随摘要出去，否则前端无从调 K11 的进度查询 / 重试端点
        assertEquals(42L, r.getData().items().get(0).jobId());
    }

    @Test
    void deleteDocument_delegatesToService() {
        Result<Void> r = knowledgeController.deleteDocument(1L, "doc-1");

        assertEquals(0, r.getCode());
        verify(knowledgeService).deleteDocument(1L, "doc-1");
    }

    @Test
    void updateBase_delegatesToService_returnsVO() {
        KnowledgeBaseUpdateRequest req = new KnowledgeBaseUpdateRequest(null, "new", null, null, null, null, null);
        when(kbAdminService.updateBase(eq(1L), any())).thenReturn(sampleVo());

        Result<KnowledgeBaseVO> r = knowledgeController.updateBase(1L, req);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1L, r.getData().id());
        verify(kbAdminService).updateBase(eq(1L), any());
    }

    @Test
    void deleteBase_delegatesToService() {
        Result<Void> r = knowledgeController.deleteBase(1L);

        assertEquals(0, r.getCode());
        verify(kbAdminService).deleteBase(1L);
    }

    @Test
    void getIndexingJob_delegatesToService_returnsVO() {
        IndexingJobVO vo = new IndexingJobVO(9L, 5L, "STORE", "FAILED", "0.8", "EMBED", "5001", "boom", 2);
        when(indexingJobService.getJob(eq(9L))).thenReturn(vo);

        Result<IndexingJobVO> r = knowledgeController.getIndexingJob(1L, 9L);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(9L, r.getData().id());
        assertEquals("FAILED", r.getData().status());
        verify(indexingJobService).getJob(9L);
    }

    @Test
    void retryIndexingJob_delegatesToService() {
        Result<Void> r = knowledgeController.retryIndexingJob(1L, 9L);

        assertEquals(0, r.getCode());
        verify(indexingJobService).retry(9L);
    }

    @Test
    void retryIndexingBatch_delegatesToService_returnsCount() {
        when(indexingJobService.retryBatch(eq("batch-1"))).thenReturn(3);

        Result<Integer> r = knowledgeController.retryIndexingBatch(1L, "batch-1");

        assertEquals(0, r.getCode());
        assertEquals(3, r.getData());
        verify(indexingJobService).retryBatch("batch-1");
    }
}
