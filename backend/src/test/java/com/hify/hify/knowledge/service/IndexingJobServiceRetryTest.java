package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.RagProperties;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.IndexingJob;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K11 索引任务重试接口单测（K9 缺口③）：仅 FAILED 可重试、批量重试计数。
 * 纯 Mockito，parseExecutor 用替身（execute 不真正跑 process，隔离续跑逻辑）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexingJobServiceRetryTest {

    @Mock IndexingJobRepository indexingJobRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentChunkRepository documentChunkRepository;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock EmbeddingService embeddingService;
    @Mock RagProperties ragProperties;
    @Mock ContentExtractor contentExtractor;
    @Mock ExecutorService parseExecutor;
    @Mock ExecutorService embeddingExecutor;

    private IndexingJobService indexingJobService;

    private IndexingJobService svc() {
        return new IndexingJobService(indexingJobRepository, documentRepository, documentChunkRepository,
                knowledgeBaseRepository, embeddingService, ragProperties, contentExtractor,
                parseExecutor, embeddingExecutor);
    }

    private IndexingJob job(Long id, IndexingJob.Status status, int retryCount) {
        IndexingJob j = new IndexingJob();
        j.setId(id);
        j.setDocId(1000L + id);
        j.setStatus(status);
        j.setRetryCount(retryCount);
        return j;
    }

    @Test
    void retry_nonFailed_throwsParamInvalid_andNoDispatch() {
        indexingJobService = svc();
        when(indexingJobRepository.findById(1L)).thenReturn(Optional.of(job(1L, IndexingJob.Status.SUCCESS, 0)));

        BizException ex = assertThrows(BizException.class, () -> indexingJobService.retry(1L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(), "仅 FAILED 可重试");
        verify(parseExecutor, never()).execute(any());
    }

    @Test
    void retry_failed_incrementsRetryCount_andDispatches() {
        indexingJobService = svc();
        IndexingJob j = job(1L, IndexingJob.Status.FAILED, 0);
        when(indexingJobRepository.findById(1L)).thenReturn(Optional.of(j));
        when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));

        indexingJobService.retry(1L);

        assertEquals(1, j.getRetryCount(), "retry_count 应 +1");
        verify(indexingJobRepository).save(any(IndexingJob.class));
        verify(parseExecutor).execute(any()); // 派发续跑
    }

    @Test
    void retryBatch_empty_returnsZero_andNoDispatch() {
        indexingJobService = svc();
        when(indexingJobRepository.findByBatchIdAndStatus("b1", IndexingJob.Status.FAILED))
                .thenReturn(List.of());

        int n = indexingJobService.retryBatch("b1");
        assertEquals(0, n);
        verify(parseExecutor, never()).execute(any());
    }

    @Test
    void retryBatch_retriesAllFailed_returnsCount() {
        indexingJobService = svc();
        IndexingJob j1 = job(10L, IndexingJob.Status.FAILED, 0);
        IndexingJob j2 = job(20L, IndexingJob.Status.FAILED, 0);
        when(indexingJobRepository.findByBatchIdAndStatus("b1", IndexingJob.Status.FAILED))
                .thenReturn(List.of(j1, j2));
        when(indexingJobRepository.findById(10L)).thenReturn(Optional.of(j1));
        when(indexingJobRepository.findById(20L)).thenReturn(Optional.of(j2));
        when(indexingJobRepository.save(any(IndexingJob.class))).thenAnswer(inv -> inv.getArgument(0));

        int n = indexingJobService.retryBatch("b1");

        assertEquals(2, n, "应返回被重试的任务数");
        verify(parseExecutor, times(2)).execute(any());
    }
}
