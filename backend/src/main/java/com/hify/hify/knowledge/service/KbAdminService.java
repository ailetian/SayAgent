package com.hify.hify.knowledge.service;

import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.web.HealthVO;
import com.hify.hify.knowledge.web.KnowledgeBaseCreateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseUpdateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseVO;
import com.hify.hify.knowledge.web.PageVO;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.List;

/**
 * 知识库「管理面」服务（K8）：建库、列表、体检。
 *
 * <p>大白话：这一类只管「库本身」——把库建出来、把库列出来、看这个库健不健康；
 * 「拿库去回答问题」在 {@link KbQaService}，「往库里塞文档」在 {@link KnowledgeService}。
 * 按 §3.7 单一职责拆分，避免 KnowledgeService 变成什么都干的巨型文件。
 */
@Slf4j
@Service
public class KbAdminService {

    /** 列表分页硬上限（§6.4 keyset 游标；防一次拉全表）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final RetrievalLogRepository retrievalLogRepository;
    private final KbAccessGuard accessGuard;

    public KbAdminService(KnowledgeBaseRepository knowledgeBaseRepository,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          RetrievalLogRepository retrievalLogRepository,
                          KbAccessGuard accessGuard) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.retrievalLogRepository = retrievalLogRepository;
        this.accessGuard = accessGuard;
    }

    /**
     * 建库（两步创建·第一步，K8）：先返回一个合法的空库，文档随后再上传。
     *
     * <p>大白话：只写一条 knowledge_base 记录（管理权归当前登录用户），不做任何索引/向量化。
     * 返回给前端的视图对象不含秘钥 / rag_config 原始 JSON（§7.11 规则37）。
     */
    public KnowledgeBaseVO createBase(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.name());
        kb.setDescription(req.description() == null ? "" : req.description());
        if (req.embeddingModel() != null) {
            kb.setEmbeddingModel(req.embeddingModel());
        }
        if (req.similarityThreshold() != null) {
            kb.setSimilarityThreshold(req.similarityThreshold().setScale(3, RoundingMode.HALF_UP));
        }
        if (req.chunkStrategy() != null) {
            kb.setChunkStrategy(req.chunkStrategy());
        }
        if (req.language() != null) {
            kb.setLanguage(req.language());
        }
        if (req.isPublic() != null) {
            kb.setIsPublic(req.isPublic());
        }
        kb.setCreatorId(accessGuard.currentUser());
        kb.setStatus(KnowledgeBase.Status.ACTIVE);
        return toVO(knowledgeBaseRepository.save(kb));
    }

    /**
     * 知识库列表（K8 keyset 游标分页 §6.4）：按 id 倒序，{@code id < lastId} 过滤。
     *
     * <p>大白话：首页不传 lastId，取最新一批；后续把上一页末 id 当 lastId 翻下一页。
     * 永远不用 offset（深翻页越翻越慢），返回 nextCursor + hasMore 供前端续翻。
     */
    public PageVO<KnowledgeBaseVO> listBases(Long lastId, int limit) {
        int pageSize = limit <= 0 ? 20 : Math.min(limit, MAX_PAGE_SIZE);
        List<KnowledgeBase> list;
        if (lastId == null) {
            list = knowledgeBaseRepository
                    .findAll(PageRequest.of(0, pageSize + 1, Sort.by(Sort.Direction.DESC, "id")))
                    .getContent();
        } else {
            list = knowledgeBaseRepository.findByIdLessThanOrderByIdDesc(
                    lastId, PageRequest.of(0, pageSize + 1));
        }
        boolean hasMore = list.size() > pageSize;
        if (hasMore) {
            list = list.subList(0, pageSize);
        }
        String nextCursor = hasMore ? String.valueOf(list.get(list.size() - 1).getId()) : null;
        return new PageVO<>(list.stream().map(this::toVO).toList(), nextCursor, hasMore);
    }

    /**
     * 知识库体检（K8）：三项指标——基础健康 / 命中质量 / 响应速度。
     *
     * <p>大白话：基础健康看「文档索引成没成功」，命中质量看「最近这些提问平均能捞到多相关的内容」，
     * 响应速度看「平均多久出结果」。数据全来自已落库的检索日志（K5 R7），不额外跑模型。
     */
    public HealthVO health(Long kbId) {
        accessGuard.requireAccessible(kbId);

        long docTotal = documentRepository.countByKbId(kbId);
        long docIndexed = documentRepository.countByKbIdAndStatus(kbId, Document.DocumentStatus.INDEXED);
        long docFailed = documentRepository.countByKbIdAndStatus(kbId, Document.DocumentStatus.FAILED);

        String basicHealth;
        double healthScore;
        if (docTotal == 0) {
            basicHealth = "EMPTY";
            healthScore = 0.0;
        } else {
            healthScore = (double) docIndexed / docTotal;
            basicHealth = docFailed > 0 ? "DEGRADED" : "HEALTHY";
        }

        List<RetrievalLog> logs = retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(kbId);
        double hitQuality = 0.0;
        double responseSpeedMs = 0.0;
        double refusalRate = 0.0;
        if (!logs.isEmpty()) {
            double sumTop = 0.0;
            double sumCost = 0.0;
            long refusedCount = 0;
            for (RetrievalLog r : logs) {
                if (r.getTopScore() != null) {
                    sumTop += r.getTopScore().doubleValue();
                }
                if (r.getCostMs() != null) {
                    sumCost += r.getCostMs();
                }
                if (Boolean.TRUE.equals(r.getRejected())) {
                    refusedCount++;
                }
            }
            hitQuality = sumTop / logs.size();
            responseSpeedMs = sumCost / logs.size();
            refusalRate = (double) refusedCount / logs.size();
        }
        return new HealthVO(basicHealth, healthScore, hitQuality, responseSpeedMs,
                docTotal, docIndexed, docFailed, logs.size(), refusalRate);
    }

    /**
     * 更新知识库（K11 收口 K8 缺口②）：仅覆盖请求中非空的字段，不整体替换。
     *
     * <p>大白话：前端改库名、阈值、切片策略等任意几项，传什么改什么；秘钥/rag_config 仍不外泄（§7.11）。
     */
    public KnowledgeBaseVO updateBase(Long id, KnowledgeBaseUpdateRequest req) {
        KnowledgeBase kb = accessGuard.requireAccessible(id);
        if (req.name() != null) {
            kb.setName(req.name());
        }
        if (req.description() != null) {
            kb.setDescription(req.description());
        }
        if (req.embeddingModel() != null) {
            kb.setEmbeddingModel(req.embeddingModel());
        }
        if (req.similarityThreshold() != null) {
            kb.setSimilarityThreshold(req.similarityThreshold().setScale(3, RoundingMode.HALF_UP));
        }
        if (req.chunkStrategy() != null) {
            kb.setChunkStrategy(req.chunkStrategy());
        }
        if (req.language() != null) {
            kb.setLanguage(req.language());
        }
        if (req.isPublic() != null) {
            kb.setIsPublic(req.isPublic());
        }
        return toVO(knowledgeBaseRepository.save(kb));
    }

    /**
     * 删除知识库（K11 收口 K8 缺口②）：软删库 + 级联软删其下文档 + 清掉 PG 里的切片。
     *
     * <p>大白话：删库不是只藏 MySQL 行——它下面的文档、PG 里的切片都得一起清，
     * 否则检索还会捞到已删库的碎片（孤儿召回）。级联软删保证"回收站可恢复"且检索立刻隔离。
     */
    public void deleteBase(Long id) {
        KnowledgeBase kb = accessGuard.requireAccessible(id);
        // 级联：先清该库下每篇文档的 PG 切片 + 软删文档行
        List<Document> docs = documentRepository.findByKbId(id);
        for (Document d : docs) {
            documentChunkRepository.deleteByDocumentId(d.getDocumentId());
            documentRepository.delete(d);
        }
        // 再软删库本身（@SQLDelete 置 deleted=1）
        knowledgeBaseRepository.delete(kb);
        log.info("knowledge base soft-deleted id={} name={} docs={}", id, kb.getName(), docs.size());
    }

    /** 知识库实体 → 视图对象（秘钥隔离：不暴露 rag_config 原始 JSON / 任何秘钥字段，§7.11 规则37）。 */
    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        return new KnowledgeBaseVO(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getEmbeddingModel(),
                kb.getEmbeddingDim(),
                kb.getSimilarityThreshold(),
                kb.getCreatorId(),
                kb.getChunkStrategy(),
                kb.getLanguage(),
                kb.getStatus(),
                kb.getIsPublic(),
                kb.getCreatedAt());
    }
}
