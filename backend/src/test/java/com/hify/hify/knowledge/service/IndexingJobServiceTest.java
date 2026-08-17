package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import com.hify.hify.knowledge.chunk.Chunk;
import com.hify.hify.knowledge.chunk.ChunkStrategy;
import com.hify.hify.knowledge.chunk.DocumentChunker;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.config.RagProperties;

import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.IndexingJob;
import com.hify.hify.knowledge.entity.KnowledgeBase;

import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K6 IndexingJobService 单测（无 Maven，纯 Mockito，不连真库 坑位2）。
 *
 * <p>覆盖验收点：失败节点可见(failStage/errorCode)、断点续跑(EMBED 失败不重解析)、批量重试、
 * checksum 去重、P5 重传删旧、独立池背压（注入同步等价 ExecutorService）。
 * 状态断言用真实的 IndexingJob/Document 实体（setter 生效），仓储全部 mock。
 */
@ExtendWith(MockitoExtension.class)
class IndexingJobServiceTest {

    @Mock
    private IndexingJobRepository indexingJobRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ContentExtractor contentExtractor;

    /** 真实默认 RAG 参数（chunkSize=800 等）。 */
    private final RagProperties ragProperties = new RagProperties();

    /** 解析/向量化池：测试注入"同步执行器"，使 submit/retry/retryBatch 立即跑完，断言确定可重跑（等价生产有界池语义）。 */
    private final ExecutorService parseExecutor = new DirectExecutorService();
    private final ExecutorService embedExecutor = new DirectExecutorService();

    private IndexingJobService service;

    /** 一个能切片的多段文本（确保产出多块）。 */
    private static final String SAMPLE_TEXT =
            "年假为5天，需提前3天申请。\n\n"
            + "婚假为10天，须提供结婚证。\n\n"
            + "病假凭医院证明，连续超2天需复核。\n\n"
            + "调休须部门负责人审批，当月有效。\n\n"
            + "加班可调休或计补贴，由员工选择。";

    @BeforeEach
    void init() {
        service = new IndexingJobService(indexingJobRepository, documentRepository,
                documentChunkRepository, knowledgeBaseRepository, embeddingService,
                ragProperties, contentExtractor, parseExecutor, embedExecutor);
    }

    /** 全局默认 RagConfig（chunkSize=800, overlap=120）。 */
    private RagConfig ragConfig() {
        return RagConfig.fromGlobal(ragProperties);
    }

    /** 真实 KnowledgeBase（AUTO 策略，rag_config=null → 回退全局）。 */
    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setChunkStrategy(KnowledgeBase.ChunkStrategy.AUTO);
        return kb;
    }

    /** 真实 Document（已落库态）。 */
    private Document doc(String documentId, long pk) {
        Document d = new Document();
        d.setId(pk);
        d.setDocumentId(documentId);
        d.setKbId(1L);
        d.setSourceRef("note.txt");
        d.setSourceType(Document.SourceType.TEXT);
        d.setStatus(Document.DocumentStatus.INDEXING);
        return d;
    }

    /** embedSlices 桩：按输入条数返回同数向量（float[1024]）。 */
    private void stubEmbedReturnsVectors() {
        when(embeddingService.embedSlices(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1024]).toList();
        });
    }

    private int chunkCount(String text) {
        return new DocumentChunker().chunk(text, ChunkStrategy.AUTO, ragConfig()).size();
    }

    // ===================== 成功主链路 =====================

    @Test
    void success_fullPipeline_marksSuccess_andStoresChunks() {
        Document d = doc("doc-uuid", 1L);
        IndexingJob job = new IndexingJob();
        job.setId(10L);
        job.setDocId(1L);
        job.setKbId(1L);
        job.setStatus(IndexingJob.Status.QUEUED);

        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        when(contentExtractor.extract(any(), any())).thenReturn(SAMPLE_TEXT);
        stubEmbedReturnsVectors();

        service.process(10L);

        // 状态机终态
        assertEquals(IndexingJob.Status.SUCCESS, job.getStatus());
        assertEquals(IndexingJob.Stage.STORE, job.getStage());
        assertEquals(Document.DocumentStatus.INDEXED, d.getStatus());
        assertEquals(job.getFailStage(), null);

        // PARSE 仅发生一次（解析没被重复调）
        verify(contentExtractor, times(1)).extract(any(), any());

        // P5：replaceChunks 原子"删旧+插新"（K11 缺陷 J/B，防半套 chunk）
        InOrder order = inOrder(documentChunkRepository);
        order.verify(documentChunkRepository).replaceChunks(eq("doc-uuid"), eq(1L), anyList());

        // 成功后清理断点暂存列（真实 Document 对象，直接断言状态而非 verify mock）
        assertEquals(null, d.getRawContent());
        assertEquals(null, d.getIndexPayload());
    }

    // ===================== 失败节点可见 =====================

    @Test
    void parseFailure_recordsFailStage_andErrorCode() {
        Document d = doc("doc-uuid", 1L);
        IndexingJob job = new IndexingJob();
        job.setId(10L);
        job.setDocId(1L);
        job.setKbId(1L);
        job.setStatus(IndexingJob.Status.QUEUED);

        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        when(contentExtractor.extract(any(), any())).thenThrow(new BizException(ErrorCode.ENCRYPTED_PDF));

        service.process(10L);

        assertEquals(IndexingJob.Status.FAILED, job.getStatus());
        assertEquals("PARSE", job.getFailStage());      // 死因精确标记在 PARSE
        assertEquals("ENCRYPTED_PDF", job.getErrorCode()); // 细分码保留
        assertEquals(Document.DocumentStatus.FAILED, d.getStatus());
        verify(documentChunkRepository, never()).saveChunk(anyString(), any(Long.class), anyInt(), anyString(), any(float[].class));
    }

    @Test
    void embedFailure_recordsFailStageEmbed_andNoAutoRetry() {
        Document d = doc("doc-uuid", 1L);
        IndexingJob job = new IndexingJob();
        job.setId(10L);
        job.setDocId(1L);
        job.setKbId(1L);
        job.setStatus(IndexingJob.Status.QUEUED);

        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        when(contentExtractor.extract(any(), any())).thenReturn(SAMPLE_TEXT);
        when(embeddingService.embedSlices(anyList())).thenThrow(new BizException(ErrorCode.EMBEDDING_FAILED));

        service.process(10L);

        assertEquals(IndexingJob.Status.FAILED, job.getStatus());
        assertEquals("EMBED", job.getFailStage());        // 死因精确标记在 EMBED
        assertEquals("EMBEDDING_FAILED", job.getErrorCode());
        assertEquals(Document.DocumentStatus.FAILED, d.getStatus());
        assertEquals(0, job.getRetryCount());             // 自动不重试
    }

    // ===================== 断点续跑 =====================

    @Test
    void retryFromEmbed_skipsParseAndChunk() {
        Document d = doc("doc-uuid", 1L);
        d.setIndexPayload("[\"块1内容\",\"块2内容\"]"); // 切片已暂存 → 续跑免重切
        IndexingJob job = new IndexingJob();
        job.setId(10L);
        job.setDocId(1L);
        job.setKbId(1L);
        job.setStatus(IndexingJob.Status.FAILED);        // 首次失败态（死于 EMBED）
        job.setFailStage("EMBED");

        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        // 首次（process）向量化抛错；重试（retry→process）命中暂存切片，向量化成功
        when(embeddingService.embedSlices(anyList()))
                .thenThrow(new BizException(ErrorCode.EMBEDDING_FAILED))   // 首次抛
                .thenReturn(List.of(new float[1024], new float[1024]));   // 重试成功

        service.process(10L); // 首次 → FAILED @EMBED（EMBED 续跑起点，跳过 PARSE+CHUNK）
        assertEquals(IndexingJob.Status.FAILED, job.getStatus());

        service.retry(10L); // 续跑：from=EMBED，按 indexPayload 直接向量化，不重解析

        // EMBED 续跑不重解析（PARSE 0 次）
        verify(contentExtractor, never()).extract(any(), any());
        assertEquals(IndexingJob.Status.SUCCESS, job.getStatus());
        assertEquals(1, job.getRetryCount());
        assertEquals(Document.DocumentStatus.INDEXED, d.getStatus());
        // 续跑时按暂存切片原子重写（replaceChunks 内部写 2 块，测试只验证它被调用）
        verify(documentChunkRepository).replaceChunks(eq("doc-uuid"), eq(1L), anyList());
    }

    @Test
    void retryFromParse_afterParseFailure_rerunsFull() {
        Document d = doc("doc-uuid", 1L);
        IndexingJob job = new IndexingJob();
        job.setId(10L);
        job.setDocId(1L);
        job.setKbId(1L);
        job.setStatus(IndexingJob.Status.FAILED);
        job.setFailStage("PARSE");

        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        // 首次解析抛错，重试解析成功
        when(contentExtractor.extract(any(), any()))
                .thenThrow(new BizException(ErrorCode.ENCRYPTED_PDF))
                .thenReturn(SAMPLE_TEXT);
        stubEmbedReturnsVectors();

        service.process(10L); // 首次 → FAILED @PARSE
        assertEquals(IndexingJob.Status.FAILED, job.getStatus());

        service.retry(10L); // 整体重跑

        verify(contentExtractor, times(2)).extract(any(), any()); // PARSE 重跑
        assertEquals(IndexingJob.Status.SUCCESS, job.getStatus());
        assertEquals(1, job.getRetryCount());
    }

    // ===================== checksum 去重 R8 =====================

    @Test
    void dedup_readyDuplicate_returnsExisting() {
        Document existing = doc("existing-uuid", 99L);
        existing.setStatus(Document.DocumentStatus.INDEXED);
        when(documentRepository.findByKbIdAndChecksumAndStatus(1L, "abc123", Document.DocumentStatus.INDEXED))
                .thenReturn(List.of(existing));

        Optional<String> hit = service.findReadyDuplicate(1L, "abc123");
        assertTrue(hit.isPresent());
        assertEquals("existing-uuid", hit.get());

        // 无校验和 → 不去重
        assertFalse(service.findReadyDuplicate(1L, null).isPresent());
        // 无重复 → 空
        when(documentRepository.findByKbIdAndChecksumAndStatus(2L, "xyz", Document.DocumentStatus.INDEXED))
                .thenReturn(List.of());
        assertFalse(service.findReadyDuplicate(2L, "xyz").isPresent());
    }

    // ===================== 批量一键重试 =====================

    @Test
    void retryBatch_retriesAllFailed_andSkipsSuccess() {
        Document dA = doc("doc-a", 1L);
        dA.setIndexPayload("[\"a1\",\"a2\"]");
        Document dB = doc("doc-b", 2L);
        dB.setIndexPayload("[\"b1\",\"b2\"]");
        Document dC = doc("doc-c", 3L);

        IndexingJob jobA = new IndexingJob();
        jobA.setId(11L); jobA.setDocId(1L); jobA.setKbId(1L);
        jobA.setStatus(IndexingJob.Status.FAILED); jobA.setFailStage("EMBED");
        IndexingJob jobB = new IndexingJob();
        jobB.setId(12L); jobB.setDocId(2L); jobB.setKbId(1L);
        jobB.setStatus(IndexingJob.Status.FAILED); jobB.setFailStage("EMBED");
        IndexingJob jobC = new IndexingJob();
        jobC.setId(13L); jobC.setDocId(3L); jobC.setKbId(1L);
        jobC.setStatus(IndexingJob.Status.SUCCESS);

        when(indexingJobRepository.findById(11L)).thenReturn(Optional.of(jobA));
        when(indexingJobRepository.findById(12L)).thenReturn(Optional.of(jobB));
        when(indexingJobRepository.findByBatchIdAndStatus("batch1", IndexingJob.Status.FAILED))
                .thenReturn(List.of(jobA, jobB));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(dA));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(dB));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb()));
        stubEmbedReturnsVectors();

        int retried = service.retryBatch("batch1");

        assertEquals(2, retried);
        assertEquals(1, jobA.getRetryCount());
        assertEquals(1, jobB.getRetryCount());
        assertEquals(IndexingJob.Status.SUCCESS, jobA.getStatus());
        assertEquals(IndexingJob.Status.SUCCESS, jobB.getStatus());
        assertEquals(IndexingJob.Status.SUCCESS, jobC.getStatus()); // 未动，保持 SUCCESS
    }

    /**
     * 同步执行器：{@code execute} 直接在当前线程跑任务，使 submit/retry/retryBatch 立即完成，
     * 断言确定可重跑（与生产中"独立有界池"语义等价，只是无并发）。
     */
    static final class DirectExecutorService extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            // no-op
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
