package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KbAccess;
import com.hify.hify.knowledge.entity.KbAccessTargetType;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.KbAccessRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.config.RagProperties;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.web.ChunkVO;
import com.hify.hify.knowledge.web.DocumentVO;
import com.hify.hify.knowledge.web.KbAccessVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;
    private final Executor embeddingExecutor;
    private final RetrievalPort retrievalPort;
    private final KbAccessRepository kbAccessRepository;

    public KnowledgeService(KnowledgeBaseRepository knowledgeBaseRepository,
                            DocumentRepository documentRepository,
                            DocumentChunkRepository documentChunkRepository,
                            EmbeddingService embeddingService,
                            @Qualifier("embeddingExecutor") Executor embeddingExecutor,
                            RetrievalPort retrievalPort,
                            KbAccessRepository kbAccessRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingService = embeddingService;
        this.embeddingExecutor = embeddingExecutor;
        this.retrievalPort = retrievalPort;
        this.kbAccessRepository = kbAccessRepository;
    }

    /**
     * 上传文档并异步向量化，返回业务 document_id。
     */
    public String uploadDocument(KnowledgeBaseUploadRequest req) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(req.kbId())
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        ensureCreatorAccess(kb);
        assertAccess(kb);

        if (req.type() == Document.SourceType.FILE
                && (req.filename() == null || !isAllowedExt(req.filename()))) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE);
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
        documentRepository.save(doc);

        List<String> texts = new ArrayList<>();
        if (req.title() != null) {
            texts.add(req.title());
        }
        if (req.content() != null) {
            texts.add(req.content());
        }

        embeddingExecutor.execute(() -> indexDocument(documentId, req.kbId(), texts));
        return documentId;
    }

    /** 异步索引：切片向量化 → 逐条写 pg → 回填 document 状态。 */
    private void indexDocument(String documentId, Long kbId, List<String> texts) {
        Document doc = documentRepository.findByDocumentId(documentId).orElse(null);
        if (doc == null) {
            log.error("indexDocument skip: document not found documentId={}", documentId);
            return;
        }
        try {
            List<String> slices = embeddingService.splitIntoChunks(texts);
            List<float[]> vectors = embeddingService.embedSlices(slices);
            int seq = 0;
            for (float[] v : vectors) {
                String slice = seq < slices.size() ? slices.get(seq) : "";
                documentChunkRepository.saveChunk(documentId, kbId, seq, slice, v);
                seq++;
            }
            doc.setStatus(Document.DocumentStatus.INDEXED);
            doc.setChunkCount(vectors.size());
            documentRepository.save(doc);
        } catch (Exception e) {
            log.error("indexDocument failed documentId={}", documentId, e);
            doc.setErrorMessage(e.getMessage());
            doc.setStatus(Document.DocumentStatus.FAILED);
            documentRepository.save(doc);
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
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        assertAccess(kb);
        double threshold = kb.getSimilarityThreshold() != null
                ? kb.getSimilarityThreshold().doubleValue()
                : RagProperties.DEFAULT_SCORE_THRESHOLD;
        List<float[]> vectors = embeddingService.embedDocuments(List.of(query));
        if (vectors.isEmpty()) {
            return List.of();
        }
        List<RetrievalPort.RetrievedChunk> chunks = retrievalPort.retrieve(vectors.get(0), kbId, topK, threshold);
        List<ChunkVO> result = new ArrayList<>(chunks.size());
        for (RetrievalPort.RetrievedChunk c : chunks) {
            result.add(new ChunkVO(c.score(), c.content(), c.documentId(), c.chunkIndex()));
        }
        return result;
    }

    private String defaultTitle(KnowledgeBaseUploadRequest req) {
        if (req.filename() != null) {
            return req.filename();
        }
        return "untitled";
    }

    /** 统一访问判权（RBAC）：管理员可访问全部；否则须存在匹配的 kb_access 授权记录。 */
    private void assertAccess(KnowledgeBase kb) {
        if (isAdmin()) {
            return;
        }
        Long kbId = kb.getId();
        if (kbId == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "知识库未持久化，无法鉴权");
        }
        List<KbAccess> grants = kbAccessRepository.findByKbId(kbId);
        boolean allowed = grants.stream().anyMatch(g ->
                (g.getTargetType() == KbAccessTargetType.USER && g.getTargetId().equals(currentUser()))
                || (g.getTargetType() == KbAccessTargetType.ROLE && currentRoles().contains(g.getTargetId())));
        if (!allowed) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
    }

    /** 首次上传时绑定创建者，并自动授予其访问权（创建者也走统一权限表 kb_access）。 */
    private void ensureCreatorAccess(KnowledgeBase kb) {
        if (kb.getCreatorId() == null) {
            String user = currentUser();
            kb.setCreatorId(user);
            knowledgeBaseRepository.save(kb);
            KbAccess grant = new KbAccess();
            grant.setKbId(kb.getId());
            grant.setTargetType(KbAccessTargetType.USER);
            grant.setTargetId(user);
            kbAccessRepository.save(grant);
        }
    }

    /** 管理员分配知识库访问权（RBAC）：按角色(ROLE)或具体人(USER)。 */
    public KbAccessVO grantAccess(Long kbId, KbAccessTargetType targetType, String targetId) {
        assertAdmin();
        knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (kbAccessRepository.existsByKbIdAndTargetTypeAndTargetId(kbId, targetType, targetId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该授权已存在");
        }
        KbAccess grant = new KbAccess();
        grant.setKbId(kbId);
        grant.setTargetType(targetType);
        grant.setTargetId(targetId);
        KbAccess saved = kbAccessRepository.save(grant);
        return new KbAccessVO(saved.getId(), saved.getKbId(), saved.getTargetType(), saved.getTargetId());
    }

    /** 列出某知识库的访问授权（仅管理员）。 */
    public List<KbAccessVO> listAccess(Long kbId) {
        assertAdmin();
        knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        return kbAccessRepository.findByKbId(kbId).stream()
                .map(g -> new KbAccessVO(g.getId(), g.getKbId(), g.getTargetType(), g.getTargetId()))
                .toList();
    }

    /** 撤销某知识库的访问授权（仅管理员，软删）。 */
    public void revokeAccess(Long kbId, Long accessId) {
        assertAdmin();
        KbAccess grant = kbAccessRepository.findByKbIdAndId(kbId, accessId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "授权记录不存在"));
        kbAccessRepository.delete(grant);
    }

    /** 是否管理员（ROLE_ADMIN）。 */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 当前登录用户的所有角色名（去 ROLE_ 前缀）。 */
    private Set<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
                .collect(Collectors.toSet());
    }

    /** 仅管理员可管理知识库访问授权。 */
    private void assertAdmin() {
        if (!isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可管理知识库访问授权");
        }
    }

    /** 取当前登录用户名（AuthFilter 将 username 写入 SecurityContext principal）。 */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return auth.getName();
    }

    private boolean isAllowedExt(String filename) {
        String lower = filename.toLowerCase();
        return ALLOWED_EXT.stream().anyMatch(lower::endsWith);
    }
}
