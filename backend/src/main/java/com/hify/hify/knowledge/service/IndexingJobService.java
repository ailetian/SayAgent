package com.hify.hify.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import com.hify.hify.knowledge.web.IndexingJobVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 异步索引流水线状态机（K6）。
 *
 * <p>大白话：每上传一个文件建一条 {@link IndexingJob}，把它从「解析中→切片中→向量化中→入库」逐步推到
 * SUCCESS/FAILED；某步挂了精确记死在哪一环（{@code failStage}/{@code errorCode}/{@code errorMessage}），
 * 并从那一环断点续跑。<b>不静默吞异常</b>——失败的文档留在列表里可见，绝不凭空消失。
 *
 * <p>关键纪律（对照 K6 验收与 AGENTS.md）：
 * <ul>
 *   <li><b>状态机 §3.9</b>：阶段 UPLOAD/PARSE/CHUNK/EMBED/STORE；状态 QUEUED/RUNNING/SUCCESS/FAILED。</li>
 *   <li><b>断点续跑 §3.9</b>：EMBED 失败不重解析切分（切片文本已暂存 {@code index_payload}，直接续向量化）；
 *       PARSE 失败整体重跑；自动不重试（防死循环），手动不限次。</li>
 *   <li><b>背压 §4.2/§7.5</b>：解析/向量化各跑独立线程池（parseExecutor / embeddingExecutor），有界队列
 *       + CallerRunsPolicy。</li>
 *   <li><b>P4 事务边界 §7.5</b>：本类<b>无 {@code @Transactional}</b>，每次 repo.save 都是各自的短事务；
 *       外部 embedding 调用发生在两次 save 之间，严格在事务外。</li>
 *   <li><b>P5 重传删旧 §10</b>：STORE 阶段先 {@code deleteByDocumentId} 再写，幂等，不残留半套 chunk。</li>
 *   <li><b>checksum 去重 R8 §10</b>：相同校验和且已 INDEXED 的文档，秒回"已存在"。</li>
 *   <li><b>日志 §4.9</b>：每个阶段打 INFO（jobId/docId/进度）。</li>
 * </ul>
 */
@Service
@Slf4j
public class IndexingJobService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};
    private static final int ERROR_MSG_MAX = 1024;

    private final IndexingJobRepository indexingJobRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    private final ContentExtractor contentExtractor;
    private final DocumentChunker chunker = new DocumentChunker();

    /** 解析/切片池（K6 新建，CPU 密集）。 */
    private final ExecutorService parseExecutor;
    /** 向量化池（复用 M1 AsyncConfig 的 embeddingExecutor，IO 密集）。 */
    private final ExecutorService embedExecutor;

    /** 按 documentId 串行化 STORE（K11 缺陷 J）：避免同一篇并发重传交叉 delete/insert 留下半套 chunk。 */
    private final ConcurrentHashMap<String, Object> docLocks = new ConcurrentHashMap<>();

    public IndexingJobService(IndexingJobRepository indexingJobRepository,
                              DocumentRepository documentRepository,
                              DocumentChunkRepository documentChunkRepository,
                              KnowledgeBaseRepository knowledgeBaseRepository,
                              EmbeddingService embeddingService,
                              RagProperties ragProperties,
                              ContentExtractor contentExtractor,
                              @Qualifier("parseExecutor") ExecutorService parseExecutor,
                              @Qualifier("embeddingExecutor") Executor embeddingExecutor) {
        this.indexingJobRepository = indexingJobRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
        this.contentExtractor = contentExtractor;
        this.parseExecutor = parseExecutor;
        this.embedExecutor = (ExecutorService) embeddingExecutor;
    }

    /** 全局默认 RAG 参数快照（库级覆盖由 kb.getEffectiveConfig 叠加）。 */
    private RagConfig globalConfig() {
        return RagConfig.fromGlobal(ragProperties);
    }

    /**
     * 派发一条索引任务到解析池（异步、火忘）。
     *
     * <p>大白话：上传接口调这个方法就立刻返回，文件在后台慢慢处理，前端轮询 job 状态看进度。
     */
    public void submit(Long jobId) {
        parseExecutor.execute(() -> process(jobId));
    }

    /**
     * 执行一条索引任务的主流程（在 parseExecutor 线程上跑；EMBED 阶段切到 embeddingExecutor）。
     *
     * <p>续跑起点：首次跑从 PARSE 开始；重试时从 {@code job.failStage} 续跑（STORE 失败退化为 EMBED 重算向量）。
     * 任何阶段抛异常都被捕获并落 FAILED，<b>绝不向上抛</b>（异步火忘，没有调用方接异常）。
     */
    public void process(Long jobId) {
        IndexingJob job = indexingJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "indexing_job not found: " + jobId));
        if (job.getStatus() == IndexingJob.Status.SUCCESS) {
            log.info("indexing skip already SUCCESS jobId={}", jobId);
            return;
        }
        Document doc = documentRepository.findById(job.getDocId())
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found for job: " + jobId));
        KnowledgeBase kb = knowledgeBaseRepository.findById(doc.getKbId())
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        RagConfig config = kb.getEffectiveConfig(globalConfig());

        // 续跑起点：失败任务从死环续跑；STORE 失败退化为 EMBED（向量未持久化，需重算）
        IndexingJob.Stage from = (job.getFailStage() != null)
                ? IndexingJob.Stage.valueOf(job.getFailStage())
                : IndexingJob.Stage.PARSE;
        if (from == IndexingJob.Stage.STORE) {
            from = IndexingJob.Stage.EMBED;
        }

        job.setStatus(IndexingJob.Status.RUNNING);
        indexingJobRepository.save(job);

        try {
            execute(job, doc, kb, config, from);
        } catch (RuntimeException e) {
            fail(job, doc, e);
        }
    }

    /**
     * 从指定阶段跑完剩余流水线。解析/切片在 parseExecutor 线程（即 process 所在线程）上同步进行；
     * 向量化投递到 embeddingExecutor，<b>两个池严格隔离</b>（不嵌套提交到同一池，避免死锁）。
     */
    private void execute(IndexingJob job, Document doc, KnowledgeBase kb, RagConfig config,
                         IndexingJob.Stage from) {
        String text = null;
        List<Chunk> chunks = null;

        if (from == IndexingJob.Stage.PARSE) {
            text = parseStage(doc, job);
            chunks = chunkStage(text, doc, kb, config, job);
        } else if (from == IndexingJob.Stage.CHUNK) {
            text = doc.getRawContent();
            if (text == null) {
                // 无文本暂存 → 退化为全量重跑（PARSE 阶段会重新写 rawContent）
                text = parseStage(doc, job);
                chunks = chunkStage(text, doc, kb, config, job);
            } else {
                chunks = chunkStage(text, doc, kb, config, job);
            }
        } else { // EMBED（含 STORE 映射来的）：跳过解析+切片，直接用暂存切片续跑
            chunks = readChunks(doc);
            if (chunks == null) {
                // 无切片暂存 → 退化为全量重跑
                text = parseStage(doc, job);
                chunks = chunkStage(text, doc, kb, config, job);
            }
        }

        // 向量化：外部调用，事务外（P4）。embedExecutor 与 parseExecutor 隔离。
        List<float[]> vectors = embedStage(chunks, doc, job);
        storeStage(doc, kb, chunks, vectors, job);

        // 成功：回填 document，清理断点暂存列
        doc.setStatus(Document.DocumentStatus.INDEXED);
        doc.setChunkCount(chunks.size());
        doc.setErrorMessage(null);
        doc.setRawContent(null);
        doc.setIndexPayload(null);
        documentRepository.save(doc);

        job.setStage(IndexingJob.Stage.STORE);
        job.setStatus(IndexingJob.Status.SUCCESS);
        job.setProgress(chunks.size() + "/" + chunks.size());
        job.setFailStage(null);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        indexingJobRepository.save(job);
        log.info("indexing SUCCESS jobId={} docId={} chunks={}", job.getId(), doc.getDocumentId(), chunks.size());
    }

    // ===================== 各阶段 =====================

    /** PARSE：取原文（TEXT/URL/内容型直接读 rawContent；FILE 走解析器）。写出 rawContent 供断点续跑。 */
    private String parseStage(Document doc, IndexingJob job) {
        job.setStage(IndexingJob.Stage.PARSE);
        indexingJobRepository.save(job);
        log.info("indexing PARSE start jobId={} docId={}", job.getId(), doc.getDocumentId());

        String text = contentExtractor.extract(doc, doc.getSourceRef());
        doc.setRawContent(text);
        documentRepository.save(doc);
        return text;
    }

    /** CHUNK：自适应切片（K3 R1），写出 index_payload 供 EMBED/STORE 断点续跑。 */
    private List<Chunk> chunkStage(String text, Document doc, KnowledgeBase kb, RagConfig config, IndexingJob job) {
        job.setStage(IndexingJob.Stage.CHUNK);
        indexingJobRepository.save(job);
        log.info("indexing CHUNK start jobId={} docId={}", job.getId(), doc.getDocumentId());

        ChunkStrategy strategy = ChunkStrategy.valueOf(kb.getChunkStrategy().name());
        List<Chunk> chunks = chunker.chunk(text, strategy, config);

        doc.setIndexPayload(chunksPayload(chunks, job.getId()));
        documentRepository.save(doc);
        job.setProgress("0/" + chunks.size());
        indexingJobRepository.save(job);
        return chunks;
    }

    /** EMBED：批量向量化（外部调用，事务外 P4）。投递到 embeddingExecutor 独立池，与解析池隔离（§4.2/§7.5 背压）。 */
    private List<float[]> embedStage(List<Chunk> chunks, Document doc, IndexingJob job) {
        job.setStage(IndexingJob.Stage.EMBED);
        indexingJobRepository.save(job);
        log.info("indexing EMBED start jobId={} docId={} chunks={}", job.getId(), doc.getDocumentId(), chunks.size());

        List<String> texts = chunks.stream().map(Chunk::content).toList();
        try {
            // 在独立向量化池上跑 IO 密集的远程调用，不占用解析线程；future.get 阻塞保持流水线顺序
            Future<List<float[]>> future = embedExecutor.submit(() -> embeddingService.embedSlices(texts));
            List<float[]> vectors = future.get();

            job.setProgress(chunks.size() + "/" + chunks.size());
            indexingJobRepository.save(job);
            return vectors;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化被中断 jobId=" + job.getId());
        } catch (ExecutionException ee) {
            // 还原外部调用抛出的真实异常（保留细分错误码，如 EMBEDDING_FAILED）
            Throwable cause = ee.getCause();
            if (cause instanceof BizException be) {
                throw be;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BizException(ErrorCode.EMBEDDING_FAILED, cause == null ? null : cause.getMessage());
        }
    }

    /** STORE：原子"删旧 + 插新"（K11 版本治理 / 防半套 chunk），落入 pg 向量库。 */
    private void storeStage(Document doc, KnowledgeBase kb, List<Chunk> chunks,
                            List<float[]> vectors, IndexingJob job) {
        job.setStage(IndexingJob.Stage.STORE);
        indexingJobRepository.save(job);
        log.info("indexing STORE start jobId={} docId={}", job.getId(), doc.getDocumentId());

        // K11 缺陷 J：按 documentId 串行化，避免并发重传交叉 delete/insert；
        // replaceChunks 在 pgTransactionManager 事务内原子"删旧+插新"（缺陷 B/K），不残留半套 chunk
        List<DocumentChunkRepository.ChunkRow> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            rows.add(new DocumentChunkRepository.ChunkRow(
                    chunks.get(i).seq(), chunks.get(i).content(), vectors.get(i)));
        }
        Object lock = docLocks.computeIfAbsent(doc.getDocumentId(), k -> new Object());
        synchronized (lock) {
            documentChunkRepository.replaceChunks(doc.getDocumentId(), doc.getKbId(), rows);
        }
    }

    // ===================== 失败处理 =====================

    /** 落 FAILED：记录死因阶段 / 细分错误码 / 原因；document 同步置 FAILED。绝不静默吞。 */
    private void fail(IndexingJob job, Document doc, Exception e) {
        IndexingJob.Stage failStage = job.getStage();
        ErrorCode code;
        String msg;
        if (e instanceof BizException be) {
            code = be.getErrorCode();   // 保留细分码（加密PDF/扫描件/损坏/向量化失败…），UI 精确报因
            msg = be.getMessage();
        } else {
            code = ErrorCode.INDEXING_JOB_FAILED; // 未知异常统一归索引失败兜底
            msg = "索引失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        log.error("indexing FAILED jobId={} stage={} code={} msg={}", job.getId(), failStage, code, msg, e);

        job.setStatus(IndexingJob.Status.FAILED);
        job.setFailStage(failStage == null ? null : failStage.name());
        job.setErrorCode(code.name());
        job.setErrorMessage(truncate(msg));
        indexingJobRepository.save(job);

        doc.setStatus(Document.DocumentStatus.FAILED);
        doc.setErrorMessage(truncate(msg));
        documentRepository.save(doc);
    }

    // ===================== 重试 / 批量重试 =====================

    /**
     * 手动重试一条 FAILED 任务：retry_count+1，从失败节点续跑（不自动重试，防死循环）。
     *
     * @throws BizException 仅当任务不是 FAILED 时抛 PARAM_INVALID
     */
    public void retry(Long jobId) {
        IndexingJob job = indexingJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "indexing_job not found: " + jobId));
        if (job.getStatus() != IndexingJob.Status.FAILED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅 FAILED 任务可重试，当前=" + job.getStatus());
        }
        job.setRetryCount(job.getRetryCount() + 1);
        indexingJobRepository.save(job);
        submit(jobId);
    }

    /**
     * 批量一键重试：把某批次内所有 FAILED 任务重新派发（各自从失败节点续跑，互不影响）。
     *
     * @param batchId 批次号（同一次上传共享）
     * @return 实际被重试的任务数
     */
    public int retryBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return 0;
        }
        List<IndexingJob> failed = indexingJobRepository.findByBatchIdAndStatus(batchId, IndexingJob.Status.FAILED);
        for (IndexingJob job : failed) {
            retry(job.getId());
        }
        return failed.size();
    }

    /**
     * 查询索引任务视图（K11 / K9 缺口③）：前端轮询进度用。
     */
    public IndexingJobVO getJob(Long jobId) {
        IndexingJob job = indexingJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "indexing_job not found: " + jobId));
        return new IndexingJobVO(
                job.getId(),
                job.getDocId(),
                job.getStage() != null ? job.getStage().name() : null,
                job.getStatus() != null ? job.getStatus().name() : null,
                job.getProgress(),
                job.getFailStage(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getRetryCount());
    }

    // ===================== checksum 去重 R8 =====================

    /**
     * 查同库内"相同校验和且已索引成功(READY)"的文档。命中即重复上传，应秒回已有文档 id。
     *
     * @param kbId     知识库 id
     * @param checksum 文件校验和（sha256）；为空直接返回空（不去重）
     * @return 已存在的文档业务 id；无重复返回 empty
     */
    public Optional<String> findReadyDuplicate(Long kbId, String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return Optional.empty();
        }
        return documentRepository
                .findByKbIdAndChecksumAndStatus(kbId, checksum, Document.DocumentStatus.INDEXED)
                .stream().findFirst().map(Document::getDocumentId);
    }

    // ===================== 工具 =====================

    /** 从 index_payload(JSON 数组) 还原切片；解析失败返回 null → 调用方退化为全量重跑。 */
    private List<Chunk> readChunks(Document doc) {
        String payload = doc.getIndexPayload();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            List<String> texts = MAPPER.readValue(payload, LIST_STRING);
            List<Chunk> chunks = new ArrayList<>(texts.size());
            int seq = 1;
            for (String t : texts) {
                chunks.add(new Chunk(seq++, t));
            }
            return chunks;
        } catch (IOException e) {
            log.warn("index_payload 解析失败，退化为全量重跑 jobId={}", doc.getId());
            return null;
        }
    }

    /** 把切片文本序列化成 JSON 数组暂存；序列化失败返回 null（仅丢失断点能力，不阻断主流程）。 */
    private String chunksPayload(List<Chunk> chunks, Long jobId) {
        try {
            List<String> texts = chunks.stream().map(Chunk::content).toList();
            return MAPPER.writeValueAsString(texts);
        } catch (IOException e) {
            log.warn("index_payload 序列化失败，放弃断点暂存 jobId={}", jobId, e);
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > ERROR_MSG_MAX ? s.substring(0, ERROR_MSG_MAX) : s;
    }
}
