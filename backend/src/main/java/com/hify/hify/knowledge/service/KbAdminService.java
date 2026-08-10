package com.hify.hify.knowledge.service;

import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.entity.AgentKbLink;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.IndexingJob;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.AgentKbLinkRepository;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.web.HealthVO;
import com.hify.hify.knowledge.web.KnowledgeBaseCreateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseUpdateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseVO;
import com.hify.hify.knowledge.web.PageVO;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** 跨模块依赖：agent 模块发布的「Agent 管理」API（仅依赖接口，符合 §3.2 跨模块纪律）。删除知识库前用它查挂载方。 */
    private final AgentService agentService;
    /** 挂载关系仓储（K7）：清挂载行用，同模块直接注入。 */
    private final AgentKbLinkRepository agentKbLinkRepository;
    /** 索引任务仓储（K6）：删除前把在途索引任务置 FAILED 用，同模块直接注入。 */
    private final IndexingJobRepository indexingJobRepository;

    public KbAdminService(KnowledgeBaseRepository knowledgeBaseRepository,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          RetrievalLogRepository retrievalLogRepository,
                          KbAccessGuard accessGuard,
                          AgentService agentService,
                          AgentKbLinkRepository agentKbLinkRepository,
                          IndexingJobRepository indexingJobRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.retrievalLogRepository = retrievalLogRepository;
        this.accessGuard = accessGuard;
        this.agentService = agentService;
        this.agentKbLinkRepository = agentKbLinkRepository;
        this.indexingJobRepository = indexingJobRepository;
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
    @Transactional
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
            syncRagConfigThreshold(kb, req.similarityThreshold());
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
     * 同步 rag_config JSON 中的 score_threshold，确保前端改阈值后聊天/probe 真正生效。
     */
    private void syncRagConfigThreshold(KnowledgeBase kb, java.math.BigDecimal threshold) {
        String raw = kb.getRagConfig();
        String newVal = String.format("%.3f", threshold.doubleValue());
        String updated;
        if (raw == null || raw.isBlank()) {
            updated = "{\"scoreThreshold\":" + newVal + "}";
        } else {
            updated = raw.replaceAll("\"scoreThreshold\"\\s*:\\s*[0-9.]+", "\"scoreThreshold\":" + newVal);
            updated = updated.replaceAll("\"score_threshold\"\\s*:\\s*[0-9.]+", "\"score_threshold\":" + newVal);
            if (updated.equals(raw)) {
                updated = raw.replaceFirst("\\{", "{\"scoreThreshold\":" + newVal + ",");
            }
        }
        kb.setRagConfig(updated);
        knowledgeBaseRepository.updateRagConfig(updated, kb.getId());
    }

    /**
     * 删除知识库（K0808 安全删除）：先校验是否被 Agent 挂载，挂载中则拦截并提示挂载方；
     * 通过校验后级联软删文档 + 清 PG 向量 + 清挂载关系 + 将在途索引任务置 FAILED。
     *
     * <p>大白话：删库绝不是只藏 MySQL 行——
     * <ul>
     *   <li><b>挂载守卫</b>：只要任意 Agent 的 knowledgeRefs（或挂载链接表）还指着本库，直接拦下，
     *       并明确告诉调用方"被哪些 Agent 挂载"，必须先把那些 Agent 的挂载卸掉才能删；</li>
     *   <li><b>在途任务</b>：把本库还排队/处理中的索引任务标 FAILED，避免删库后流水线继续往 PG 写孤儿向量；</li>
     *   <li><b>挂载清理</b>：软删挂载链接行 + 从所有 Agent 的 knowledgeRefs 摘除，双源一致（消除 K0808 暴露的"删完 Agent 还指向已删库"隐患）；</li>
     *   <li><b>级联清场</b>：软删文档 + 按文档清 PG 切片，再按 kb_id 兜底清任何漏网切片（孤儿回收）；</li>
     *   <li><b>事务</b>：整段落在 MySQL 默认事务里，中途异常整体回滚，绝不留下"半套已删"。</li>
     * </ul>
     */
    @Transactional
    public void deleteBase(Long id) {
        KnowledgeBase kb = accessGuard.requireAccessible(id);

        // ① 挂载守卫：字段引用（聊天检索用）与链接表（隔离过滤用）任一非空即拦截
        List<AgentVO> mountedByField = agentService.findAgentsByKnowledgeRef(id);
        List<AgentKbLink> mountedByLink = agentKbLinkRepository.findByKbId(id);
        if (!mountedByField.isEmpty() || !mountedByLink.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            mountedByField.forEach(a -> names.add(a.name()));
            mountedByLink.forEach(l -> {
                try {
                    names.add(agentService.getAgent(l.getAgentId()).name());
                } catch (BizException ignored) {
                    // 链接指向已软删的 Agent：忽略，不影响守卫结论
                }
            });
            // 细节里只放「枚举文案说不出的信息」：哪个库、被谁挂着。
            // 枚举本身已经说过"已被 Agent 挂载，请先卸载"，此处不再重复，避免响应 message 啰嗦。
            String where = names.isEmpty()
                    ? "挂载关系记录指向的 Agent 已不存在，请联系管理员清理残留链接"
                    : String.join("、", names);
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_IN_USE,
                    "「" + kb.getName() + "」当前挂载方：" + where);
        }

        // ② 在途索引任务（排队/处理中）置 FAILED，避免删库后继续写 PG 向量形成孤儿
        List<IndexingJob> inFlight = indexingJobRepository.findByKbIdAndStatusIn(id,
                List.of(IndexingJob.Status.QUEUED, IndexingJob.Status.RUNNING));
        for (IndexingJob job : inFlight) {
            job.setStatus(IndexingJob.Status.FAILED);
            job.setFailStage(job.getStage() == null ? null : job.getStage().name());
            job.setErrorCode("KB_DELETED");
            job.setErrorMessage("知识库已删除，索引任务终止");
            indexingJobRepository.save(job);
        }

        // ③ 清挂载关系：链接表软删 + Agent 字段摘除（双源一致）
        List<AgentKbLink> links = agentKbLinkRepository.findByKbId(id);
        for (AgentKbLink link : links) {
            agentKbLinkRepository.delete(link); // 软删（@SQLDelete → deleted=1）
        }
        agentService.removeKnowledgeRefEverywhere(id); // 字段兜底清理

        // ④ 级联：软删文档 + 按文档清 PG 切片
        List<Document> docs = documentRepository.findByKbId(id);
        for (Document d : docs) {
            documentChunkRepository.deleteByDocumentId(d.getDocumentId());
            documentRepository.delete(d);
        }
        documentChunkRepository.deleteByKbId(id); // 兜底：清任何漏网切片，杜绝孤儿召回

        // ⑤ 软删库本身（@SQLDelete 置 deleted=1）
        knowledgeBaseRepository.delete(kb);
        log.info("knowledge base soft-deleted id={} name={} docs={} mountedLinks={} inFlightJobs={}",
                id, kb.getName(), docs.size(), links.size(), inFlight.size());
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
