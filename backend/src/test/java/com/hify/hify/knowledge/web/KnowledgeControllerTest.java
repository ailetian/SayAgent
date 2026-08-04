package com.hify.hify.knowledge.web;

import com.hify.hify.common.Result;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeController 控制层单测（M5/T5，纯 Mockito，无 Spring 上下文）。
 *
 * <p>大白话：T5 的硬纪律是「Controller 极薄——只收请求、调 Service、装 Result 盒子」。
 * 这里把 KnowledgeService 替身，断言控制器只是把请求原样转交给 service，并把 service 的结果装盒返回。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeService knowledgeService;

    @InjectMocks
    private KnowledgeController knowledgeController;

    /** KnowledgeBaseUploadRequest 构造顺序：kbId, type, filename, title, content, sourceUrl。 */
    private KnowledgeBaseUploadRequest uploadReq() {
        return new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "kb", "hello", null);
    }

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
}
