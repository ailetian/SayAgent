package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.RagProperties;
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
import com.hify.hify.knowledge.web.DocumentSummaryVO;
import com.hify.hify.knowledge.web.DocumentVO;
import com.hify.hify.knowledge.web.PageVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库文档上传服务（M5 T3）。
 *
 * <p>大白话：上传文档先做两道校验（知识库存在、文件类型在白名单），落一条 document（状态 INDEXING），
 * 再把正文交给 embeddingExecutor 线程池异步切片向量化、逐条写 pg 向量库，
 * 成功改 INDEXED、失败改 FAILED（状态可经 {@link #getDocumentStatus} 轮询）。
 */
@Service
@Slf4j
public class KnowledgeService {

    private static final List<String> ALLOWED_EXT = List.of(".txt", ".md", ".pdf");

    /** 列表分页硬上限（§6.4 keyset 游标；防一次拉全表）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;
    private final RetrievalPort retrievalPort;
    private final IndexingJobRepository indexingJobRepository;
    private final IndexingJobService indexingJobService;
    private final KbAccessGuard accessGuard;

    public KnowledgeService(KnowledgeBaseRepository knowledgeBaseRepository,
                            DocumentRepository documentRepository,
                            DocumentChunkRepository documentChunkRepository,
                            EmbeddingService embeddingService,
                            RetrievalPort retrievalPort,
                            IndexingJobRepository indexingJobRepository,
                            IndexingJobService indexingJobService,
                            KbAccessGuard accessGuard) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingService = embeddingService;
        this.retrievalPort = retrievalPort;
        this.indexingJobRepository = indexingJobRepository;
        this.indexingJobService = indexingJobService;
        this.accessGuard = accessGuard;
    }

    /**
     * 上传文档并接入异步索引流水线，返回业务 document_id（去重命中则返回已有 id）。
     *
     * <p>大白话：先查同库同校验和是否已有"索引成功"的文档（R8 去重，秒回"已存在"），否则建一条
     * document（索引中）+ 一条 indexing_job（排队），派发到流水线后台处理；前端轮询 job 看进度。
     */
    public String uploadDocument(KnowledgeBaseUploadRequest req) {
        return beginIndexing(req, UUID.randomUUID().toString()).documentId();
    }

    /**
     * 批量上传（≤10 文件）：一次上传共享一个 batch_id，每文件一条独立 job（互不影响），
     * 返回每条对应的 document_id 列表（去重命中为已有 id）。
     */
    public List<String> uploadDocuments(List<KnowledgeBaseUploadRequest> reqs) {
        String batchId = UUID.randomUUID().toString();
        List<String> ids = new ArrayList<>(reqs.size());
        for (KnowledgeBaseUploadRequest req : reqs) {
            ids.add(beginIndexing(req, batchId).documentId());
        }
        return ids;
    }

    /** 单条索引接入：去重 → 建/更新 document+job → 派发。返回文档 id 与是否命中去重。 */
    private UploadResult beginIndexing(KnowledgeBaseUploadRequest req, String batchId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(req.kbId())
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        accessGuard.ensureCreator(kb);
        accessGuard.assertAccess(kb);

        if (req.type() == Document.SourceType.FILE
                && (req.filename() == null || !isAllowedExt(req.filename()))) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        String checksum = computeChecksum(req);

        // K11 更新/重传分支：前端带旧 documentId → 复用同一篇身份（不新建）
        if (req.documentId() != null) {
            return beginUpdate(req, kb, batchId, checksum);
        }

        // R8 去重：同库 + 同校验和 + 已索引成功(READY) → 秒回"已存在"，不重复算力
        Optional<String> dup = indexingJobService.findReadyDuplicate(req.kbId(), checksum);
        if (dup.isPresent()) {
            log.info("indexing dedup hit kbId={} checksum={} existing={}", req.kbId(), checksum, dup.get());
            return new UploadResult(dup.get(), true);
        }

        String documentId = UUID.randomUUID().toString();
        Document doc = new Document();
        doc.setDocumentId(documentId);
        doc.setKbId(req.kbId());
        doc.setTitle(req.title() != null ? req.title() : defaultTitle(req));
        doc.setSourceType(req.type());
        doc.setSourceRef(req.type() == Document.SourceType.URL ? req.sourceUrl() : req.filename());
        doc.setStatus(Document.DocumentStatus.INDEXING);
        doc.setChunkCount(0);
        doc.setChecksum(checksum);
        doc.setRawContent(req.content()); // 原始文本暂存，流水线 PARSE 阶段读取（断点续跑用）
        doc.setSizeBytes(req.content() != null ? (long) req.content().length() : 0L);
        documentRepository.save(doc);

        IndexingJob job = new IndexingJob();
        job.setDocId(doc.getId());
        job.setKbId(req.kbId());
        job.setBatchId(batchId);
        job.setStage(IndexingJob.Stage.UPLOAD);
        job.setStatus(IndexingJob.Status.QUEUED);
        job.setProgress("0/0");
        indexingJobRepository.save(job);

        // 派发到流水线（火忘，前端轮询进度）
        indexingJobService.submit(job.getId());
        log.info("indexing dispatched jobId={} docId={} kbId={} batchId={}", job.getId(), documentId, req.kbId(), batchId);
        return new UploadResult(documentId, false);
    }

    /**
     * 更新/重传分支（K11 版本治理核心）。
     *
     * <p>大白话：前端在「重新上传」时带着这篇文档的旧业务 id 来，我们<b>复用同一篇</b>（不新建 UUID），
     * 流水线 STORE 阶段会"先删旧切片、再写新切片"（{@code DocumentChunkRepository.replaceChunks}），
     * 旧版本内容自然被撕掉、问答只命中新版，杜绝版本污染。越权与软删校验在此收口：
     * <ul>
     *   <li>文档不存在（含已软删，{@code @SQLRestriction} 过滤）→ {@code RESOURCE_NOT_FOUND}；</li>
     *   <li>该 document 不属于目标知识库 → {@code FORBIDDEN}（防改他人库文档，K11 缺陷 C）；</li>
     *   <li>同 id + 同校验和 + 已索引成功 → 内容没变，<b>跳过整段重切重嵌</b>（省一次 embedding，K11 缺陷 D）。</li>
     * </ul>
     *
     * @return 复用的文档业务 id（与新建分支返回口径一致）
     */
    private UploadResult beginUpdate(KnowledgeBaseUploadRequest req, KnowledgeBase kb, String batchId, String checksum) {
        Document doc = documentRepository.findByDocumentId(req.documentId())
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found"));
        // K11 缺陷 C：越权 + 归属校验（软删文档因 @SQLRestriction 查不到而自动拒）
        if (!doc.getKbId().equals(req.kbId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "该 document 不属于目标知识库");
        }
        // K11 缺陷 D：仅更新路径 + 同 id 同校验和 + 已索引成功 → 跳过重算，保留现有切片
        if (doc.getChecksum() != null && doc.getChecksum().equals(checksum)
                && doc.getStatus() == Document.DocumentStatus.INDEXED) {
            log.info("indexing update skip (same checksum) kbId={} docId={}", req.kbId(), doc.getDocumentId());
            return new UploadResult(doc.getDocumentId(), true);
        }
        doc.setTitle(req.title() != null ? req.title() : defaultTitle(req));
        doc.setSourceType(req.type());
        doc.setSourceRef(req.type() == Document.SourceType.URL ? req.sourceUrl() : req.filename());
        doc.setStatus(Document.DocumentStatus.INDEXING);
        doc.setChunkCount(0);
        doc.setChecksum(checksum);
        doc.setRawContent(req.content());
        doc.setSizeBytes(req.content() != null ? (long) req.content().length() : 0L);
        documentRepository.save(doc);

        IndexingJob job = new IndexingJob();
        job.setDocId(doc.getId());
        job.setKbId(req.kbId());
        job.setBatchId(batchId);
        job.setStage(IndexingJob.Stage.UPLOAD);
        job.setStatus(IndexingJob.Status.QUEUED);
        job.setProgress("0/0");
        indexingJobRepository.save(job);

        indexingJobService.submit(job.getId());
        log.info("indexing update dispatched jobId={} docId={} kbId={} batchId={}",
                job.getId(), doc.getDocumentId(), req.kbId(), batchId);
        return new UploadResult(doc.getDocumentId(), false);
    }

    /** 上传结果（含是否命中去重）。 */
    private record UploadResult(String documentId, boolean deduplicated) {
    }

    /** 计算去重校验和：有正文用正文，否则用「标题|来源」兜底（FILE 无内容时较弱，K8 接字节后改 sha256(bytes)）。 */
    private String computeChecksum(KnowledgeBaseUploadRequest req) {
        String base = req.content() != null ? req.content()
                : (req.title() + "|" + (req.sourceUrl() != null ? req.sourceUrl() : req.filename()));
        return sha256(base);
    }

    /** sha256 十六进制串。 */
    private String sha256(String s) {
        if (s == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.SYS_ERROR, "SHA-256 不可用");
        }
    }

    /**
     * 查询文档索引状态（供上传后轮询）。返回与 T3 验收一致的 DONE/PROCESSING/FAILED
     * （内部枚举映射：INDEXED→DONE、INDEXING→PROCESSING、FAILED→FAILED、UPLOADED→UPLOADED）。
     */
    public String getDocumentStatus(String documentId) {
        Document doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found"));
        return switch (doc.getStatus()) {
            case INDEXED -> "DONE";
            case INDEXING -> "PROCESSING";
            case FAILED -> "FAILED";
            case UPLOADED -> "UPLOADED";
        };
    }

    /**
     * 根据文档 ID 返回文档视图对象（M5/T5 上传接口响应，避免直接序列化实体，规则37）。
     *
     * @param documentId 文档业务 ID
     * @return 文档视图对象
     */
    public DocumentVO getDocumentVO(String documentId) {
        Document doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "documentId=" + documentId));
        return new DocumentVO(doc.getDocumentId(), doc.getStatus().name(), doc.getChunkCount());
    }

    /**
     * 知识库检索（M5/T5）：把查询文本向量化后，按余弦相似度取 Top-k 片段。
     *
     * @param kbId  知识库 ID，用于隔离（已合入 T1 实际落地为 document_chunk.kb_id 列）
     * @param query 查询文本
     * @param topK  返回片段数
     * @return 按相似度降序的片段视图对象列表；无命中或查询为空时返回空列表，不返回 null
     */
    public List<ChunkVO> retrieve(Long kbId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        KnowledgeBase kb = accessGuard.requireAccessible(kbId);
        double threshold = kb.getSimilarityThreshold() != null
                ? kb.getSimilarityThreshold().doubleValue()
                : RagProperties.DEFAULT_SCORE_THRESHOLD;
        List<float[]> vectors = embeddingService.embedDocuments(List.of(query));
        if (vectors.isEmpty()) {
            return List.of();
        }
        // K11 缺陷 A：从 MySQL 取本库未删 doc id（@SQLRestriction(deleted=0) 已过滤），
        // 下推 PG 用 document_id IN(...) 过滤孤儿 chunk（被软删文档的切片绝不召回）
        List<String> allowedDocIds = documentRepository.findByKbId(kbId).stream()
                .map(Document::getDocumentId).toList();
        List<RetrievalPort.RetrievedChunk> chunks = retrievalPort.retrieve(vectors.get(0), allowedDocIds, topK, threshold);
        List<ChunkVO> result = new ArrayList<>(chunks.size());
        for (RetrievalPort.RetrievedChunk c : chunks) {
            result.add(new ChunkVO(c.score(), c.content(), c.documentId(), c.chunkIndex()));
        }
        return result;
    }

    /**
     * 文档列表（K11 / K9 缺口①）：keyset 游标分页，返回<b>本库</b>未软删的文档摘要。
     *
     * <p>大白话：前端文档管理页用它列出某库下的文档（含 id / 标题 / 状态 / 切片数 / 最近索引任务 id），
     * 「重新上传」按钮拿列表里的 documentId 调上传接口即可复用同一篇身份，
     * 「重试索引」按钮拿 jobId 调 K11 的重试端点。
     *
     * <p>两条硬约束：
     * <ol>
     *   <li>查询必须带 {@code kbId}（§6.4 keyset 范例即「父维度 + id &lt; lastId」）。
     *       {@code accessGuard} 只管「能不能看这个库」，管不了「别把邻居库的文档一起列出来」；</li>
     *   <li>任务 id 走一次 IN 批量查后在内存归并，禁止在循环里逐条查（N+1）。</li>
     * </ol>
     */
    public PageVO<DocumentSummaryVO> listDocuments(Long kbId, Long lastId, int limit) {
        accessGuard.requireAccessible(kbId);
        int pageSize = limit <= 0 ? 20 : Math.min(limit, MAX_PAGE_SIZE);
        List<Document> list;
        if (lastId == null) {
            list = documentRepository.findByKbIdOrderByIdDesc(kbId, PageRequest.of(0, pageSize + 1));
        } else {
            list = documentRepository.findByKbIdAndIdLessThanOrderByIdDesc(kbId, lastId,
                    PageRequest.of(0, pageSize + 1));
        }
        boolean hasMore = list.size() > pageSize;
        if (hasMore) {
            list = list.subList(0, pageSize);
        }
        String nextCursor = hasMore ? String.valueOf(list.get(list.size() - 1).getId()) : null;
        Map<Long, Long> latestJobIdByDocId = latestJobIds(list);
        List<DocumentSummaryVO> items = list.stream().map(d -> new DocumentSummaryVO(
                d.getDocumentId(),
                d.getTitle(),
                d.getStatus().name(),
                d.getChunkCount(),
                d.getSizeBytes(),
                d.getUpdatedAt() != null ? d.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null,
                latestJobIdByDocId.get(d.getId())))
                .toList();
        return new PageVO<>(items, nextCursor, hasMore);
    }

    /**
     * 归并出「每篇文档最近一条索引任务 id」（K11）。
     *
     * <p>大白话：一篇文档重传几次就有几条 job，只有 id 最大的那条代表当前状态。
     * 整页一次 IN 查完再在内存里取最大值，避免逐条查库。
     *
     * @param docs 本页文档
     * @return document.id -&gt; 最近 job id；无任务的文档不出现在 map 中
     */
    private Map<Long, Long> latestJobIds(List<Document> docs) {
        if (docs.isEmpty()) {
            return Map.of();
        }
        List<Long> docIds = docs.stream().map(Document::getId).toList();
        Map<Long, Long> latest = new HashMap<>();
        for (IndexingJob job : indexingJobRepository.findByDocIdIn(docIds)) {
            latest.merge(job.getDocId(), job.getId(), Math::max);
        }
        return latest;
    }

    /**
     * 删除文档（K11 / K9 缺口①）：软删 MySQL 文档行 + 清掉 PG 里它的全部切片（孤儿 chunk 不再召回）。
     *
     * <p>大白话：删文档不是"只把 MySQL 行藏起来"——PG 的切片表没有软删列，必须显式清掉，
     * 否则检索还会捞到这篇已删文档的碎片。越权与归属在此收口（同更新分支）。
     */
    public void deleteDocument(Long kbId, String documentId) {
        accessGuard.requireAccessible(kbId);
        Document doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found"));
        if (!doc.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "该 document 不属于目标知识库");
        }
        documentChunkRepository.deleteByDocumentId(doc.getDocumentId());
        documentRepository.delete(doc); // @SQLDelete 软删
        log.info("document soft-deleted kbId={} docId={}", kbId, documentId);
    }

    private String defaultTitle(KnowledgeBaseUploadRequest req) {
        if (req.filename() != null) {
            return req.filename();
        }
        return "untitled";
    }

    private boolean isAllowedExt(String filename) {
        String lower = filename.toLowerCase();
        return ALLOWED_EXT.stream().anyMatch(lower::endsWith);
    }
}
