package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KbAccess;
import com.hify.hify.knowledge.entity.KbAccessTargetType;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.KbAccessRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.web.ChunkVO;
import com.hify.hify.knowledge.web.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentChunkRepository documentChunkRepository;
    @Mock EmbeddingService embeddingService;
    @Mock RetrievalPort retrievalPort;
    @Mock KbAccessRepository kbAccessRepository;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        loginAs("tester"); // 默认：普通用户，无角色、非管理员
        Executor sync = Runnable::run;
        knowledgeService = new KnowledgeService(kbRepository, documentRepository, documentChunkRepository,
                embeddingService, sync, retrievalPort, kbAccessRepository);
    }

    /** 把当前登录身份写进 SecurityContext（AuthFilter 会把 username 放进 principal）。 */
    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    // ===== 上传链路（T3）=====

    @Test
    void testUploadDocument_validText_savesDocumentAndIndexesChunks() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(documentRepository.findByDocumentId(any())).thenReturn(Optional.of(new Document()));
        when(embeddingService.splitIntoChunks(any())).thenReturn(List.of("s1", "s2"));
        when(embeddingService.embedSlices(any())).thenReturn(List.of(new float[1024], new float[1024]));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 模拟创建者授权已入库

        String docId = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null));

        assertEquals(36, docId.length());
        verify(documentRepository, times(2)).save(any());
        verify(documentChunkRepository, times(2)).saveChunk(any(), any(), anyInt(), any(), any());
        assertEquals("tester", kb.getCreatorId(), "首个上传者应被绑定为 creator");
        verify(kbAccessRepository).save(any(KbAccess.class)); // 创建者自动获得访问权
    }

    @Test
    void testUploadDocument_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(99L, Document.SourceType.TEXT, null, "t", "c", null)));
        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testUploadDocument_unsupportedFileType_throwsUnsupportedFileType() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 模拟创建者授权已入库

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.FILE, "a.exe", "t", "c", null)));
        assertEquals(ErrorCode.UNSUPPORTED_FILE_TYPE, ex.getErrorCode());
    }

    @Test
    void testUploadDocument_embeddingFails_marksDocumentFailed() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(documentRepository.findByDocumentId(any())).thenReturn(Optional.of(new Document()));
        when(embeddingService.splitIntoChunks(any())).thenReturn(List.of("s1", "s2"));
        when(embeddingService.embedSlices(any())).thenThrow(new RuntimeException("embedding boom"));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 模拟创建者授权已入库

        knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null));

        verify(documentRepository, times(2)).save(any());
        verify(documentChunkRepository, never()).saveChunk(any(), any(), anyInt(), any(), any());
    }

    @Test
    void testUploadDocument_nonCreatorNoGrant_throwsForbidden() {
        KnowledgeBase owned = new KnowledgeBase();
        owned.setId(1L);
        owned.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(owned));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(List.of()); // 无授权记录

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非创建者且无授权应被拒");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void testUploadDocument_asyncIndexing_savesChunksOnAnotherThread() throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(documentRepository.findByDocumentId(any())).thenReturn(Optional.of(new Document()));
        when(embeddingService.splitIntoChunks(any())).thenReturn(List.of("s1", "s2"));
        when(embeddingService.embedSlices(any())).thenReturn(List.of(new float[1024], new float[1024]));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 模拟创建者授权已入库

        CountDownLatch done = new CountDownLatch(1);
        Executor latchExecutor = r -> new Thread(r).start();
        knowledgeService = new KnowledgeService(kbRepository, documentRepository, documentChunkRepository,
                embeddingService, latchExecutor, retrievalPort, kbAccessRepository);
        knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null));
        done.await(2, TimeUnit.SECONDS);

        verify(documentChunkRepository, times(2)).saveChunk(any(), any(), anyInt(), any(), any());
    }

    // ===== 检索链路（T4 + RBAC）=====

    @Test
    void testRetrieve_queryEmpty_returnsEmptyList() {
        List<ChunkVO> r = knowledgeService.retrieve(1L, "   ", 5);
        assertEquals(0, r.size());
    }

    @Test
    void testRetrieve_creatorCanAccess_passesThresholdToRetrievalPort() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("tester");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 创建者授权存在
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);

        assertEquals(1, result.size());
        assertEquals(0.9, result.get(0).score());
        verify(retrievalPort).retrieve(any(float[].class), eq(1L), eq(5), anyDouble());
    }

    @Test
    void testRetrieve_noGrant_throwsForbidden() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(List.of()); // 无授权

        BizException ex = assertThrows(BizException.class, () -> knowledgeService.retrieve(1L, "问题", 5));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "无授权应被拒");
        verify(retrievalPort, never()).retrieve(any(float[].class), any(), anyInt(), anyDouble());
    }

    @Test
    void testRetrieve_adminCanAccessAnyKb() {
        loginAs("admin", "ADMIN");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);
        assertEquals(1, result.size());
        verify(retrievalPort).retrieve(any(float[].class), eq(1L), eq(5), anyDouble());
    }

    @Test
    void testRetrieve_roleGrant_allowsUserWithRole() {
        loginAs("tester", "USER");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.ROLE, "USER"))); // 按角色 USER 授权
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);
        assertEquals(1, result.size());
    }

    @Test
    void testRetrieve_userGrant_allowsSpecificUser() {
        loginAs("tester");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.findByKbId(1L)).thenReturn(
                List.of(grant(1L, KbAccessTargetType.USER, "tester"))); // 按具体人授权
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);
        assertEquals(1, result.size());
    }

    @Test
    void testRetrieve_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(7L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> knowledgeService.retrieve(7L, "问题", 5));
        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    // ===== 状态/视图（T3/T5）=====

    @Test
    void testGetDocumentStatus_indexed_returnsDone() {
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.INDEXED);
        when(documentRepository.findByDocumentId("d1")).thenReturn(Optional.of(doc));

        assertEquals("DONE", knowledgeService.getDocumentStatus("d1"));
    }

    @Test
    void testGetDocumentVO_indexed_returnsVoWithStatusAndChunkCount() {
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.INDEXED);
        doc.setChunkCount(3);
        when(documentRepository.findByDocumentId("d1")).thenReturn(Optional.of(doc));

        DocumentVO vo = knowledgeService.getDocumentVO("d1");
        assertEquals("INDEXED", vo.status());
        assertEquals(3, vo.chunkCount());
    }

    // ===== 访问授权管理（仅 ADMIN，RBAC）=====

    @Test
    void testGrantAccess_adminCreatesGrant() {
        loginAs("admin", "ADMIN");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(kbAccessRepository.existsByKbIdAndTargetTypeAndTargetId(1L, KbAccessTargetType.ROLE, "USER"))
                .thenReturn(false);
        when(kbAccessRepository.save(any(KbAccess.class))).thenAnswer(inv -> {
            KbAccess g = inv.getArgument(0);
            g.setId(99L); // 模拟 JPA 回填主键
            return g;
        });

        var vo = knowledgeService.grantAccess(1L, KbAccessTargetType.ROLE, "USER");

        assertEquals("USER", vo.getTargetId());
        verify(kbAccessRepository).save(any(KbAccess.class));
    }

    @Test
    void testGrantAccess_nonAdmin_throwsForbidden() {
        loginAs("tester"); // 非管理员

        BizException ex = assertThrows(BizException.class,
                () -> knowledgeService.grantAccess(1L, KbAccessTargetType.ROLE, "USER"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非管理员不可分配授权");
    }

    @Test
    void testRevokeAccess_adminDeletes() {
        loginAs("admin", "ADMIN");
        KbAccess grant = grant(1L, KbAccessTargetType.USER, "tester");
        grant.setId(5L);
        when(kbAccessRepository.findByKbIdAndId(1L, 5L)).thenReturn(Optional.of(grant));

        knowledgeService.revokeAccess(1L, 5L);

        verify(kbAccessRepository).delete(grant);
    }

    @Test
    void testListAccess_adminReturnsGrants() {
        loginAs("admin", "ADMIN");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        KbAccess g1 = grant(1L, KbAccessTargetType.USER, "tester");
        g1.setId(1L);
        KbAccess g2 = grant(1L, KbAccessTargetType.ROLE, "USER");
        g2.setId(2L);
        when(kbAccessRepository.findByKbId(1L)).thenReturn(List.of(g1, g2));

        var list = knowledgeService.listAccess(1L);
        assertEquals(2, list.size());
    }

    // ===== 工具 =====

    private KbAccess grant(Long kbId, KbAccessTargetType type, String targetId) {
        KbAccess g = new KbAccess();
        g.setKbId(kbId);
        g.setTargetType(type);
        g.setTargetId(targetId);
        return g;
    }
}
