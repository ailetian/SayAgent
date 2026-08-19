package com.sayagent.knowledge.service;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.config.RagProperties;
import com.sayagent.knowledge.dto.KnowledgeBaseUploadRequest;
import com.sayagent.knowledge.entity.Document;
import com.sayagent.knowledge.entity.IndexingJob;
import com.sayagent.knowledge.entity.KnowledgeBase;
import com.sayagent.knowledge.repository.DocumentChunkRepository;
import com.sayagent.knowledge.repository.DocumentRepository;
import com.sayagent.knowledge.repository.IndexingJobRepository;
import com.sayagent.knowledge.parser.DocumentParsers;
import com.sayagent.knowledge.repository.KnowledgeBaseRepository;
import com.sayagent.knowledge.retriever.RetrievalPort;
import com.sayagent.knowledge.web.ChunkVO;
import com.sayagent.knowledge.web.DocumentSummaryVO;
import com.sayagent.knowledge.web.DocumentVO;
import com.sayagent.knowledge.web.PageVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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

    private static final List<String> ALLOWED_EXT = List.of(".txt", ".md", ".pdf", ".docx", ".doc");

    /** 列表分页硬上限（§6.4 keyset 游标；防一次拉全表）。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 源文档落盘目录（网站下的目录，部署时通过 sayagent.sources-dir 配置；默认相对工作目录的 data/sources）。 */
    @Value("${sayagent.sources-dir:data/sources}")
    private String sourcesDir;

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

    /**
     * 二进制文件上传（PDF/DOCX/MD/TXT）：接收真实字节，按扩展名路由 {@link DocumentParsers} 用 Tika 解析成纯文本，
     * 再走与文本上传完全一致的索引流水线。
     *
     * <p>大白话：此前前端用 {@code readAsText} 把文件当纯文本读，PDF/DOCX 这类二进制会被读成乱码，
     * 且 Tika 解析器在生产的取原文实现里从未被调用（死代码）。这里把「真实字节 → 解析 → 入库」的钩子接上
     * （见 {@code DocumentContentExtractor} 注释预留位），加密 PDF / 扫描件 / 格式损坏等细分错误码现在会真正触发。
     *
     * @return 每条文件对应的文档 id 列表（顺序与入参一致）
     */
    public List<String> uploadFiles(Long kbId, List<MultipartFile> files) {
        List<String> ids = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            ids.add(uploadFile(kbId, f, null));
        }
        return ids;
    }

    /**
     * 单文件二进制上传：读字节 → 校验大小/类型白名单 → 解析为纯文本 → 复用文本上传流水线。
     *
     * <p>解析在上传期同步完成（字节只在此时存在），结果文本写入 document.rawContent，
     * 后续异步流水线的 PARSE 阶段直接复用该文本，断点续跑与去重逻辑与文本上传完全一致。
     */
    public String uploadFile(Long kbId, MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件为空");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE, "单文件超过 20MB 上限");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !isAllowedExt(filename)) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE, filename);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYS_ERROR, "读取上传文件失败：" + e.getMessage());
        }
        if (bytes.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "文件内容为空");
        }
        // 扩展名路由 + 魔数兜底（改后缀绕过由解析器内部拦截）
        String text = DocumentParsers.get(DocumentParsers.detectType(filename)).parse(bytes, filename);
        KnowledgeBaseUploadRequest req = new KnowledgeBaseUploadRequest(
                kbId, Document.SourceType.FILE, filename,
                title != null ? title : filename,
                text, null, null);
        String documentId = uploadDocument(req);
        storeSource(documentId, filename, bytes); // 落盘原始字节，供「查看源文档」回看
        return documentId;
    }

    /**
     * 落盘源文档原始字节，供「查看源文档」回看；并回填 source_ref / mime_type / size_bytes。
     *
     * <p>大白话：解析在主流程已同步完成，这里仅做文件持久化（写入 sayagent.sources-dir 目录，文件名用业务
     * documentId，天然去重且和切片表 document_id 同口径）。落盘失败只影响「源文档预览」这一项，
     * 不影响索引流水线（检索照常工作），故仅记日志不抛异常。
     */
    private void storeSource(String documentId, String filename, byte[] bytes) {
        try {
            Path dir = Paths.get(sourcesDir);
            Files.createDirectories(dir);
            String ext = extensionOf(filename);
            Path target = dir.resolve(documentId + (ext.isEmpty() ? "" : "." + ext));
            Files.write(target, bytes); // 幂等：重传同 docId 覆盖旧文件
            Document doc = documentRepository.findByDocumentId(documentId).orElse(null);
            if (doc != null) {
                doc.setSourceRef(target.toString().replace('\\', '/'));
                doc.setMimeType(mimeOf(filename));
                doc.setSizeBytes((long) bytes.length);
                documentRepository.save(doc);
            }
        } catch (IOException e) {
            log.warn("源文档落盘失败 docId={}：{}", documentId, e.getMessage());
        }
    }

    /** 取扩展名（小写，不含点）；无则返回空串。 */
    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /** 扩展名 → MIME（显式映射，未知回退 octet-stream；非魔法数字，属数据映射）。 */
    private static String mimeOf(String filename) {
        return switch (extensionOf(filename)) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "txt" -> "text/plain; charset=utf-8";
            case "md" -> "text/markdown; charset=utf-8";
            default -> "application/octet-stream";
        };
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

    /**
     * 查看/下载源文档：FILE 且已落盘 → 流式返回原始字节（PDF 内联预览，其余附件下载）；
     * 无二进制源（TEXT / 旧上传未落盘）→ 回退返回 raw_content 文本，保证「查看源文档」始终可用。
     */
    public ResponseEntity<Resource> getSource(Long kbId, String documentId) {
        accessGuard.requireAccessible(kbId);
        Document doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found"));
        if (!doc.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "该 document 不属于目标知识库");
        }
        String name = safeName(doc.getTitle());
        Path file = resolveSourceFile(doc); // 先按 DB 路径找，再按命名约定兜底
        if (file != null && Files.exists(file)) {
            String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
            boolean inline = "application/pdf".equals(mime);
            String ext = extOf(file.toString());
            FileSystemResource res = new FileSystemResource(file.toFile());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            (inline ? "inline" : "attachment") + "; filename=\"" + name + ext + "\"")
                    .contentLength(file.toFile().length())
                    .body(res);
        }
        // 回退：返回原文文本（TEXT 或旧上传无二进制源）
        String text = doc.getRawContent() != null ? doc.getRawContent() : "";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource res = new ByteArrayResource(data);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".txt\"")
                .contentLength(data.length)
                .body(res);
    }

    /**
     * 列出某文档入库后的全部切片（按 seq 升序），供前端「切片预览」面板——直接回答
     * 「被切成了哪几段、有没有切坏」，无需开脚本/接口即可排查「程序问题 vs 库里真没有」。
     */
    public List<ChunkVO> getDocumentChunks(Long kbId, String documentId) {
        accessGuard.requireAccessible(kbId);
        Document doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "document not found"));
        if (!doc.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "该 document 不属于目标知识库");
        }
        List<DocumentChunkRepository.DocumentChunk> rows = documentChunkRepository.findByDocumentId(documentId);
        List<ChunkVO> result = new ArrayList<>(rows.size());
        for (DocumentChunkRepository.DocumentChunk c : rows) {
            result.add(new ChunkVO(0.0, c.content(), c.documentId(), c.seq()));
        }
        return result;
    }

    /** 文件名安全化：剔除路径分隔等非法字符。 */
    private static String safeName(String title) {
        if (title == null) return "document";
        return title.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 解析源文件路径，按两层策略：① DB sourceRef 若是完整路径且文件存在则直接用；
     * ② 按命名约定 {sourcesDir}/{documentId}.{ext} 兜底（ext 从文件名截取）。
     * 都找不到返回 null，由调用方走文本回退。
     *
     * <p>大白话：DB 里的 sourceRef 有时只存了文件名（storeSource 的 DB 更新受异步流水线干扰），
     * 但文件已按 documentId 命名落在 sourcesDir，所以按约定构造路径兜底找一次。
     */
    private Path resolveSourceFile(Document doc) {
        // 策略①：sourceRef 是完整路径且文件存在
        if (doc.getSourceRef() != null) {
            Path p = Paths.get(doc.getSourceRef());
            if (Files.exists(p)) return p;
        }
        // 策略②：按命名约定构造 {sourcesDir}/{documentId}.{ext} 兜底
        if (doc.getSourceRef() != null && sourcesDir != null) {
            String ext = extOf(doc.getSourceRef());
            if (!ext.isEmpty()) {
                Path p = Paths.get(sourcesDir, doc.getDocumentId() + ext);
                if (Files.exists(p)) return p;
            }
        }
        return null;
    }

    /** 从路径取扩展名（含点），无则返回空串。 */
    private static String extOf(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }
}
