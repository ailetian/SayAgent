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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K11 版本治理 / 软删预过滤 / 越权收口 单测（纯 Mockito，不连真库 §7.10）。
 *
 * <p>覆盖 K11 修复的缺陷：
 * <ul>
 *   <li><b>缺陷 C（越权/归属）</b>：更新 / 删除带旧 documentId 时，校验该文档属于目标库，否则 FORBIDDEN；</li>
 *   <li><b>缺陷 D（checksum 误用）</b>：同 id + 同校验和 + 已索引成功 → 跳过重切重嵌，省一次 embedding；</li>
 *   <li><b>缺陷 A（孤儿召回）</b>：retrieve 先把本库未删 doc id 下推 PG，软删文档的切片绝不召回；</li>
 *   <li><b>版本污染</b>：重传复用同一篇身份（不新建 UUID），流水线 STORE 原子替换切片；</li>
 *   <li><b>半套 chunk</b>：删文档同步清 PG 切片。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeServiceK11Test {

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
        loginAs("tester");
        knowledgeService = new KnowledgeService(kbRepository, documentRepository, documentChunkRepository,
                embeddingService, retrievalPort, indexingJobRepository, indexingJobService,
                new KbAccessGuard(kbRepository));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    private KnowledgeBase ownedKb(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreatorId("tester");
        kb.setSimilarityThreshold(new BigDecimal("0.5"));
        return kb;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===================== 重传 / 版本治理 =====================

    @Test
    void upload_withDocumentId_reusesSameIdentity_andDispatchesJob() {
        KnowledgeBase kb = ownedKb(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        Document doc = new Document();
        doc.setDocumentId("doc-old");
        doc.setKbId(1L);
        doc.setStatus(Document.DocumentStatus.INDEXING);
        doc.setChecksum("old-different");
        when(documentRepository.findByDocumentId("doc-old")).thenReturn(Optional.of(doc));

        String id = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "brand-new content", null, "doc-old"));

        assertEquals("doc-old", id, "重传必须复用同一篇业务 id（不新建 UUID）");
        verify(indexingJobService).submit(any()); // 内容变了 → 派发新索引任务
    }

    @Test
    void upload_sameChecksumAndIndexed_skipsReindex_savesNothing() {
        KnowledgeBase kb = ownedKb(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        String cs = sha256("same content");
        Document doc = new Document();
        doc.setDocumentId("doc-old");
        doc.setKbId(1L);
        doc.setStatus(Document.DocumentStatus.INDEXED);
        doc.setChecksum(cs); // 与请求内容同校验和
        when(documentRepository.findByDocumentId("doc-old")).thenReturn(Optional.of(doc));

        String id = knowledgeService.uploadDocument(
                new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "same content", null, "doc-old"));

        assertEquals("doc-old", id, "内容没变应秒回同一 id");
        verify(indexingJobService, never()).submit(any()); // 跳过整段重切重嵌
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void upload_updateDocumentNotFound_throwsResourceNotFound() {
        KnowledgeBase kb = ownedKb(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(documentRepository.findByDocumentId("doc-x")).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, "doc-x")));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode(), "软删/不存在的文档应被拒");
    }

    @Test
    void upload_updateWrongKb_throwsForbidden() {
        KnowledgeBase kb = ownedKb(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        Document doc = new Document();
        doc.setDocumentId("doc-old");
        doc.setKbId(2L); // 属于另一个库
        doc.setChecksum("x");
        when(documentRepository.findByDocumentId("doc-old")).thenReturn(Optional.of(doc));

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.uploadDocument(new KnowledgeBaseUploadRequest(1L, Document.SourceType.TEXT, null, "t", "c", null, "doc-old")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "跨库改他人文档应被拒（缺陷 C）");
    }

    // ===================== 删除文档（清孤儿 chunk） =====================

    @Test
    void deleteDocument_success_clearsPgChunks_andSoftDeletes() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));
        Document doc = new Document();
        doc.setDocumentId("doc-d");
        doc.setKbId(1L);
        when(documentRepository.findByDocumentId("doc-d")).thenReturn(Optional.of(doc));

        knowledgeService.deleteDocument(1L, "doc-d");

        verify(documentChunkRepository).deleteByDocumentId("doc-d"); // PG 切片必须清
        verify(documentRepository).delete(doc); // MySQL 软删
    }

    @Test
    void deleteDocument_notFound_throwsResourceNotFound() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));
        when(documentRepository.findByDocumentId("doc-x")).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.deleteDocument(1L, "doc-x"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void deleteDocument_wrongKb_throwsForbidden() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));
        Document doc = new Document();
        doc.setDocumentId("doc-d");
        doc.setKbId(2L);
        when(documentRepository.findByDocumentId("doc-d")).thenReturn(Optional.of(doc));

        BizException ex = assertThrows(BizException.class, () ->
                knowledgeService.deleteDocument(1L, "doc-d"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "不能删他人库的文档");
    }

    // ===================== 文档列表 keyset =====================

    @Test
    void listDocuments_keyset_returnsPageOfSummaries() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));
        Document doc = new Document();
        doc.setId(10L);
        doc.setDocumentId("doc-a");
        doc.setTitle("t");
        doc.setStatus(Document.DocumentStatus.INDEXED);
        doc.setChunkCount(3);
        doc.setSizeBytes(100L);
        when(documentRepository.findByKbIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(doc));

        var page = knowledgeService.listDocuments(1L, null, 20);

        assertEquals(1, page.items().size());
        assertEquals("doc-a", page.items().get(0).docId());
        assertEquals("INDEXED", page.items().get(0).status());
    }

    /**
     * 隔离回归：文档列表必须只查本库。
     *
     * <p>早期实现首页用 {@code findAll}、翻页用不带 kbId 的 {@code findByIdLessThan}，
     * 结果是 A 库用户能看到 B 库文档（accessGuard 只挡「能不能看这个库」，挡不住「顺带列出邻居库」）。
     * 本用例锁死：两条分支都必须走带 kbId 的查询，且 kbId 原样透传。
     */
    @Test
    void listDocuments_mustScopeByKbId_noCrossKbLeak() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));

        knowledgeService.listDocuments(1L, null, 20);
        verify(documentRepository).findByKbIdOrderByIdDesc(eq(1L), any(PageRequest.class));

        knowledgeService.listDocuments(1L, 99L, 20);
        verify(documentRepository).findByKbIdAndIdLessThanOrderByIdDesc(eq(1L), eq(99L), any(PageRequest.class));

        // 绝不允许再出现「不带 kbId 的全表分页」
        verify(documentRepository, never()).findAll(any(PageRequest.class));
    }

    /**
     * jobId 回填：前端凭它调 K11 的进度查询 / 重试端点。
     *
     * <p>一篇文档重传多次会有多条 job，只有 id 最大的那条代表当前状态；
     * 且整页只允许一次 IN 查询（禁止循环里逐条查，N+1）。
     */
    @Test
    void listDocuments_attachesLatestJobId_singleBatchQuery() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(ownedKb(1L)));
        Document doc = new Document();
        doc.setId(10L);
        doc.setDocumentId("doc-a");
        doc.setStatus(Document.DocumentStatus.INDEXED);
        when(documentRepository.findByKbIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(doc));

        IndexingJob older = new IndexingJob();
        older.setId(7L);
        older.setDocId(10L);
        IndexingJob newer = new IndexingJob();
        newer.setId(9L);
        newer.setDocId(10L);
        when(indexingJobRepository.findByDocIdIn(List.of(10L))).thenReturn(List.of(older, newer));

        var page = knowledgeService.listDocuments(1L, null, 20);

        assertEquals(9L, page.items().get(0).jobId(), "应取最新一条 job");
        verify(indexingJobRepository, times(1)).findByDocIdIn(anyList());
    }

    // ===================== 检索软删预过滤（缺陷 A） =====================

    @Test
    void retrieve_softDeletePreFilter_pushesAllowedDocIdsToRetrievalPort() {
        KnowledgeBase kb = ownedKb(1L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        Document alive = new Document();
        alive.setDocumentId("doc-a");
        // 只返回未删文档（@SQLRestriction 在真库自动过滤，这里用桩模拟）
        when(documentRepository.findByKbId(1L)).thenReturn(List.of(alive));
        when(embeddingService.embedDocuments(List.of("问题"))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(retrievalPort.retrieve(any(float[].class), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("doc-a", 0, "c", 0.9)));

        List<ChunkVO> result = knowledgeService.retrieve(1L, "问题", 5);

        assertEquals(1, result.size());
        // 下推 PG 的 allowedDocIds 必须仅含未删的 doc-a（孤儿 chunk 不召回）
        verify(retrievalPort).retrieve(any(float[].class), eq(List.of("doc-a")), eq(5), anyDouble());
    }
}
