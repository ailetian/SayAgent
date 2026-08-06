package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.IndexingJob;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.web.ChunkVO;
import com.hify.hify.knowledge.web.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeService（上传 / 检索 / 视图）单测。
 *
 * <p>K7 退役 KbAccess 后，访问权模型收敛为「管理权 = 知识库创建者 或 管理员」；查询权委托 Agent 挂载
 * （见 {@link MountServiceTest}）。本测试覆盖：上传绑定创建者 + 派发索引任务、R8 去重、检索的创建者/管理员
 * 鉴权、空查询短路等。所有 repo 均为 mock，不连真库（§7.10）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeServiceTest {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentChunkRepository documentChunkRepository;
    @Mock EmbeddingService embeddingService;
    @Mock RetrievalPort retrievalPort;
    @Mock IndexingJobRepository indexingJobRepository;
    @Mock IndexingJobService indexingJobService;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        loginAs("tester"); // 默认：普通用户，无角色、非管理员
        // KbAccessGuard 用真身（K8 §3.7 拆分抽出的共享判权组件）：它只读 SecurityContext + kbRepository，
        // mock 掉反而测不到「创建者/管理员」这条真实判权链路。
        knowledgeService = new KnowledgeService(kbRepository, documentRepository, documentChunkRepository,
                embeddingService, retrievalPort, indexingJobRepository, indexingJobService,
                new KbAccessGuard(kbRepository));
        // K11 检索预过滤：retrieve 内部会取本库未删 doc id 下推 PG，默认返回一条避免 NPE
        when(documentRepository.findByKbId(anyLong())).thenReturn(List.of(new Document()));
    }

    /** 把当前登录身份写进 SecurityContext（AuthFilter 会把 username 放进 principal）。 */
    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    // ===== 上传链路（K6 异步 + K7 鉴权）=====

    @Test
    void uploadDocument_bindCreatorOnFirstUpload_andDispatchesJob() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L); // creatorId 初始为 null
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(indexingJobService.findReadyDuplicate(eq(1L), any())).thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));

        String docId = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, null));

        assertEquals(36, docId.length());
        // 首个上传者被绑定为 creator（K7：不再写 kb_access 授权）
        assertEquals("tester", kb.getCreatorId(), "首个上传者应被绑定为 creator");
        verify(indexingJobRepository).save(any(IndexingJob.class));
        verify(indexingJobService).submit(any()); // 派发到流水线
    }

    @Test
    void uploadDocument_nonCreatorNonAdmin_throwsForbidden() {
        KnowledgeBase owned = new KnowledgeBase();
        owned.setId(1L);
        owned.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(owned));

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, null)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非创建者且非管理员应被拒");
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadDocument_adminCanUploadAnyKb() {
        loginAs("admin", "ADMIN");
        KnowledgeBase owned = new KnowledgeBase();
        owned.setId(1L);
        owned.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(owned));
        when(indexingJobService.findReadyDuplicate(eq(1L), any())).thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));

        String docId = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, null));
        assertEquals(36, docId.length());
        verify(indexingJobService).submit(any());
    }

    @Test
    void uploadDocument_dedupReturnsExistingId_withoutSaving() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(indexingJobService.findReadyDuplicate(eq(1L), any())).thenReturn(Optional.of("existing-doc-id"));

        String docId = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, null));
        assertEquals("existing-doc-id", docId, "R8 去重应秒回已有 id");
        verify(documentRepository, never()).save(any(Document.class));
        verify(indexingJobService, never()).submit(any());
    }

    @Test
    void uploadDocument_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(99L, Document.SourceType.TEXT, null, "t", "c", null, null)));
        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    // ===== 检索链路（K7 创建者/管理员鉴权）=====

    @Test
    void retrieve_emptyQuery_returnsEmptyList() {
        List<ChunkVO> r = knowledgeService.retrieve(1L, "   ", 5);
        assertEquals(0, r.size());
    }

    @Test
    void retrieve_creatorCanAccess_passesToRetrievalPort() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("tester");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("d1", 0, "c1", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);

        assertEquals(1, result.size());
        assertEquals(0.9, result.get(0).score());
        verify(retrievalPort).retrieve(any(float[].class), anyList(), eq(5), anyDouble());
    }

    @Test
    void retrieve_nonCreatorNonAdmin_throwsForbidden() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatorId("other");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        BizException ex = assertThrows(BizException.class, () -> knowledgeService.retrieve(1L, "问题", 5));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非创建者且非管理员应被拒");
        verify(retrievalPort, never()).retrieve(any(float[].class), any(), anyInt(), anyDouble());
    }

    @Test
    void retrieve_adminCanAccessAnyKb() {
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
        verify(retrievalPort).retrieve(any(float[].class), anyList(), eq(5), anyDouble());
    }

    @Test
    void retrieve_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(7L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> knowledgeService.retrieve(7L, "问题", 5));
        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    // ===== 状态/视图（T3/T5）=====

    @Test
    void getDocumentStatus_indexed_returnsDone() {
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.INDEXED);
        when(documentRepository.findByDocumentId("d1")).thenReturn(Optional.of(doc));

        assertEquals("DONE", knowledgeService.getDocumentStatus("d1"));
    }

    @Test
    void getDocumentVO_indexed_returnsVoWithStatusAndChunkCount() {
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.INDEXED);
        doc.setChunkCount(3);
        when(documentRepository.findByDocumentId("d1")).thenReturn(Optional.of(doc));

        DocumentVO vo = knowledgeService.getDocumentVO("d1");
        assertEquals("INDEXED", vo.status());
        assertEquals(3, vo.chunkCount());
    }
}
