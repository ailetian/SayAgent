package com.hify.hify.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.knowledge.service.KbRetrievalService;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.route.ProviderRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 问答编排（K5）：阈值拒答 R3 + Query Rewriting R4 + Small-to-Big R5 + 溯源 R6 + 检索日志 §5.6。
 *
 * <p>大白话：这是「用户提问 → 给答案」的总指挥。流程：
 * <ol>
 *   <li><b>R4 改写</b>：先让 {@link QueryRewriter} 把口语/指代问题翻成完整查询（不调 LLM，规则版）。</li>
 *   <li><b>P1 隔离</b>：没有任何挂载库 → 直接 {@code NO_KB} 拒答（连检索都省了）。</li>
 *   <li><b>K4 混合检索</b>：调 {@link RetrievalPort#retrieveHybrid} 拿融合排序的候选。</li>
 *   <li><b>R3 阈值拒答</b>：候选最高<b>语义余弦</b>低于 {@code score_threshold} → {@code BELOW_THRESHOLD} 拒答，
 *       绝不让 LLM 拿三分像的上下文硬编（E4：先取 TOP_K 再阈值过滤，两步独立）。</li>
 *   <li><b>R5 Small-to-Big</b>：命中的每块按 {@code seq} 拼前/后各 {@code contextExpand} 块，喂完整上下文。</li>
 *   <li><b>R6 溯源</b>：每块标 {@code [来源i]}（文档名 + 段落），答案由 LLM 在句末引用。</li>
 *   <li><b>§5.6 记账</b>：每次问答写一条 {@link RetrievalLog}（含拒答分型 + top_candidates）。</li>
 * </ol>
 *
 * <p>§4 LLM 治理：生成答案走 M3 {@link ProviderRouter#route}（线上 API 抽象，非 chat；按默认模型自动降级）；失败统一抛
 * {@link ErrorCode#LLM_CALL_FAILED}。当前 {@code ProviderClient.send} 接口未暴露 temperature，
 * rag 生成温度（计划 0.1）属 M3 后续扩展，本类按接口原样调用，不在 K5 擅自加参（跨模块只依赖接口）。
 *
 * <p>§7.3 异常：检索失败由 K4 抛 {@code RETRIEVAL_FAILED} 向上传播；LLM 失败由 M3 抛 {@code LLM_CALL_FAILED}。
 * §4.9 调用留痕：关键分支打 INFO（query/rewritten/mountedKb/hitCount/topScore/threshold/costMs）。
 */
@Service
@Slf4j
public class RagQueryService {

    /** 拒绝回答的统一话术（知识库确实查不到时，不编造）。 */
    private static final String REFUSAL_MESSAGE = "知识库中未检索到相关信息，暂无法回答。";

    /** 兜底系统提示（强约束：只依据资料、引用 [来源i]、不足则明说）。 */
    private static final String SYSTEM_PROMPT =
            "你是企业知识库问答助手。只能依据下方【资料】作答，禁止编造或引入资料外信息。"
            + "引用资料时，在对应句末标注 [来源i]（i 为资料编号）。"
            + "若资料不足以回答，直接回复：" + REFUSAL_MESSAGE;

    /** 意图网关拦截话术（K0808 T3，写死、零成本、不调 LLM、不写 RetrievalLog）。 */
    private static final String CANNED_GREETING = "你好！我是你的企业知识库助手，有什么可以帮你的吗？";
    private static final String CANNED_IDENTITY = "我是 SayAgent 企业知识库问答助手，可以基于你挂载的知识库回答相关问题。";
    private static final String CANNED_MEANINGLESS = "抱歉，我没太理解你的问题，可以换种说法再问我吗？";
    private static final String CANNED_TOOL = "当前暂不支持该操作，我可以帮你检索知识库内容，请直接提问。";

    private final QueryRewriter queryRewriter;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentRepository documentRepository;
    private final ProviderRouter providerRouter;
    private final RetrievalLogRepository retrievalLogRepository;
    private final KbRetrievalService kbRetrievalService;
    private final QueryIntentClassifier queryIntentClassifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagQueryService(QueryRewriter queryRewriter,
                            DocumentChunkRepository documentChunkRepository,
                            DocumentRepository documentRepository,
                            ProviderRouter providerRouter,
                            RetrievalLogRepository retrievalLogRepository,
                            KbRetrievalService kbRetrievalService,
                            QueryIntentClassifier queryIntentClassifier) {
        this.queryRewriter = queryRewriter;
        this.documentChunkRepository = documentChunkRepository;
        this.documentRepository = documentRepository;
        this.providerRouter = providerRouter;
        this.retrievalLogRepository = retrievalLogRepository;
        this.kbRetrievalService = kbRetrievalService;
        this.queryIntentClassifier = queryIntentClassifier;
    }

    /**
     * 执行一次 RAG 问答。
     *
     * @param req 问答请求（query / history / mountedKbIds / agentId / ragConfig 必填；providerConfig 为兼容保留字段，实际生成统一走 ProviderRouter 路由）
     * @return 问答结果（拒答或带 [来源i] 的答案）
     */
    public RagAnswer query(RagQueryRequest req) {
        long start = System.currentTimeMillis();
        // —— 意图网关（K0808 T2/T3）：问答最前先分类，非问题直接友好响应 ——
        // 省去无谓检索，也避免「你好」被算成「答不上来」污染拒答率（呼应 K14 V7）。
        // 判定主依据仍是「清洗后是否非空」（QueryIntentClassifier 内部复用 QueryRewriter.stripFiller），
        // 故「你好，年假怎么请」清洗后剩「年假怎么请」→ QUESTION，不误杀。
        // 注意：GREETING/IDENTITY/MEANINGLESS/TOOL 直接返回 canned，不检索、不调 LLM、不写 RetrievalLog。
        QueryIntentClassifier.Intent intent = queryIntentClassifier.classify(req.query(), req.history());
        if (intent == QueryIntentClassifier.Intent.GREETING) {
            return RagAnswer.canned(CANNED_GREETING);
        }
        if (intent == QueryIntentClassifier.Intent.IDENTITY) {
            return RagAnswer.canned(CANNED_IDENTITY);
        }
        if (intent == QueryIntentClassifier.Intent.MEANINGLESS) {
            return RagAnswer.canned(CANNED_MEANINGLESS);
        }
        if (intent == QueryIntentClassifier.Intent.TOOL) {
            return RagAnswer.canned(CANNED_TOOL);
        }
        // 其余为 QUESTION：继续原检索流程（R4 起）

        // R4：口语改写 + 指代消解
        String rewritten = queryRewriter.rewrite(req.query(), req.history());
        log.info("RagQuery start queryLen={} rewrittenLen={} mountedKb={} agentId={}",
                req.query().length(), rewritten.length(),
                req.mountedKbIds() == null ? 0 : req.mountedKbIds().size(), req.agentId());

        // P1：无挂载库 → NO_KB 拒答（§5.6 仍记账，kb_id 置空）
        if (req.mountedKbIds() == null || req.mountedKbIds().isEmpty()) {
            RetrievalLog rlog = buildLog(req, rewritten, start, true, RetrievalLog.RefusalReason.NO_KB,
                    null, req.ragConfig().scoreThreshold(), null, null, null);
            retrievalLogRepository.save(rlog);
            return RagAnswer.refuse(RetrievalLog.RefusalReason.NO_KB, req.ragConfig().scoreThreshold());
        }

        // K4：混合检索（统一走 KbRetrievalService 唯一入口，避免多份 replicate）
        List<RetrievalResult> results = kbRetrievalService.retrieve(req.mountedKbIds(), rewritten, req.ragConfig());
        if (results.isEmpty()) {
            RetrievalLog rlog = buildLog(req, rewritten, start, true, RetrievalLog.RefusalReason.NO_HIT,
                    null, req.ragConfig().scoreThreshold(), null, null, null);
            retrievalLogRepository.save(rlog);
            return RagAnswer.refuse(RetrievalLog.RefusalReason.NO_HIT, req.ragConfig().scoreThreshold());
        }

        // R3：阈值拒答（取候选里最高语义余弦；FTS-only 片段 semanticScore=0.0）
        double topScore = results.stream().mapToDouble(RetrievalResult::semanticScore).max().orElse(0.0);
        double threshold = req.ragConfig().scoreThreshold();
        if (topScore < threshold) {
            String topCandidates = toTopCandidatesJson(results);
            String hitChunks = toHitChunksJson(results);
            String scores = toScoresJson(results);
            RetrievalLog rlog = buildLog(req, rewritten, start, true, RetrievalLog.RefusalReason.BELOW_THRESHOLD,
                    topScore, threshold, hitChunks, scores, topCandidates);
            retrievalLogRepository.save(rlog);
            log.info("RagQuery refused(BELOW_THRESHOLD) topScore={} threshold={} costMs={}",
                    topScore, threshold, System.currentTimeMillis() - start);
            return RagAnswer.refuse(RetrievalLog.RefusalReason.BELOW_THRESHOLD, threshold);
        }

        // 通过闸门：取最终 top-n 块
        int finalTopN = req.ragConfig().finalTopN();
        List<RetrievalResult> top = results.stream().limit(finalTopN).collect(Collectors.toList());

        // R5 Small-to-Big：每块拼前后 contextExpand 块
        List<ContextBlock> blocks = expandContext(top, req.ragConfig().contextExpand());

        // R6 溯源：为每个命中块建来源
        List<SourceRef> sources = buildSources(blocks);

        // 调 M3 LLM 生成带 [来源i] 的答案
        String prompt = buildPrompt(rewritten, blocks, sources);
        LlmResponse resp;
        try {
            resp = providerRouter.route(List.of(
                    new ChatMessage("system", SYSTEM_PROMPT),
                    new ChatMessage("user", prompt)));
        } catch (BizException e) {
            // LLM 调用失败：记账（answer=null）后向上抛，由全局异常处理器翻译
            String hitChunks = toHitChunksJson(top);
            String scores = toScoresJson(top);
            RetrievalLog rlog = buildLog(req, rewritten, start, false, null,
                    topScore, threshold, hitChunks, scores, null);
            retrievalLogRepository.save(rlog);
            log.warn("RagQuery LLM call failed: {}", e.getMessage());
            throw e;
        }

        String answer = resp != null && resp.getContent() != null ? resp.getContent() : "";
        // 若 LLM 未带来源标记（退化成拒答话术），仍按成功记账，但答案本身已说明不足
        String hitChunks = toHitChunksJson(top);
        String scores = toScoresJson(top);
        RetrievalLog rlog = buildLog(req, rewritten, start, false, null,
                topScore, threshold, hitChunks, scores, null);
        rlog.setAnswer(answer);
        retrievalLogRepository.save(rlog);

        log.info("RagQuery answered hitCount={} sourceCount={} topScore={} costMs={}",
                top.size(), sources.size(), topScore, System.currentTimeMillis() - start);
        return new RagAnswer(false, answer, null, sources, topScore, threshold, false);
    }

    /**
     * R5 Small-to-Big：对命中的每块，按 seq 取 [seq-expand, seq+expand] 范围的前后块拼成大块。
     *
     * @param top     融合排序后的 top-n 命中
     * @param expand  前后各扩几块（来自 RagConfig.contextExpand）
     * @return 与 top 一一对应的上下文块（按 seq 升序拼接 content）
     */
    private List<ContextBlock> expandContext(List<RetrievalResult> top, int expand) {
        List<ContextBlock> blocks = new ArrayList<>(top.size());
        for (RetrievalResult r : top) {
            int lo = expand <= 0 ? r.chunkIndex() : Math.max(0, r.chunkIndex() - expand);
            int hi = r.chunkIndex() + expand;
            // 拷贝一份再排序：仓储实现可能返回不可变列表（如 Mockito 桩 List.of），避免 UnsupportedOperationException
            List<DocumentChunkRepository.DocumentChunk> neighbors =
                    new ArrayList<>(documentChunkRepository.findByDocumentIdAndSeqBetween(r.documentId(), lo, hi));
            neighbors.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
            String context = neighbors.stream().map(DocumentChunkRepository.DocumentChunk::content)
                    .collect(Collectors.joining("\n"));
            blocks.add(new ContextBlock(r.documentId(), r.chunkIndex(), r.semanticScore(), context));
        }
        return blocks;
    }

    /** R6：为每个命中块构造来源引用（编号 1..n，对应 prompt 里的 [来源i]）。 */
    private List<SourceRef> buildSources(List<ContextBlock> blocks) {
        List<SourceRef> sources = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            ContextBlock b = blocks.get(i);
            String title = documentRepository.findByDocumentId(b.documentId())
                    .map(Document::getTitle).orElse(b.documentId());
            sources.add(new SourceRef(i + 1, b.documentId(), b.seq(), title));
        }
        return sources;
    }

    /** 构建发给 LLM 的 user 消息（问题 + 带 [来源i] 标记的资料块）。 */
    private String buildPrompt(String rewritten, List<ContextBlock> blocks, List<SourceRef> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(rewritten).append("\n\n【资料】\n");
        for (SourceRef s : sources) {
            // 找到该来源对应的上下文块
            ContextBlock block = blocks.get(s.index() - 1);
            sb.append("[来源").append(s.index()).append("] 《").append(s.title()).append("》·第")
                    .append(s.seq()).append("段\n").append(block.context()).append("\n\n");
        }
        return sb.toString();
    }

    // ===================== 检索日志构建 =====================

    private RetrievalLog buildLog(RagQueryRequest req, String rewritten, long start,
                                  boolean rejected, RetrievalLog.RefusalReason reason,
                                  Double topScore, double threshold,
                                  String hitChunks, String scores, String topCandidates) {
        RetrievalLog log = new RetrievalLog();
        // 多库联合检索时取代表 kb（优先显式指定，否则取首个挂载库）；NO_KB 时为 null（列已放空）
        Long kbId = req.primaryKbId() != null ? req.primaryKbId()
                : (req.mountedKbIds() != null && !req.mountedKbIds().isEmpty()
                    ? req.mountedKbIds().get(0) : null);
        log.setKbId(kbId);
        log.setAgentId(req.agentId());
        log.setQuery(req.query());
        log.setRewritten(rewritten);
        log.setRejected(rejected);
        log.setRefusalReason(reason);
        log.setHitChunks(hitChunks);
        log.setScores(scores);
        log.setTopCandidates(topCandidates);
        log.setCostMs(System.currentTimeMillis() - start);
        if (topScore != null) {
            log.setTopScore(BigDecimal.valueOf(topScore));
        }
        log.setThreshold(BigDecimal.valueOf(threshold));
        return log;
    }

    // ===================== JSON 序列化（§5.6 留痕，禁止敏感字段） =====================

    private String toHitChunksJson(List<RetrievalResult> results) {
        List<Map<String, Object>> list = results.stream().limit(5).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentId", r.documentId());
            m.put("seq", r.chunkIndex());
            m.put("source", r.source().name());
            // content 可能较长，截断到 200 字便于日志复盘（不截断语义）
            String c = r.content();
            m.put("content", c == null ? "" : (c.length() > 200 ? c.substring(0, 200) + "…" : c));
            return m;
        }).collect(Collectors.toList());
        return writeJson(list);
    }

    private String toScoresJson(List<RetrievalResult> results) {
        List<Double> list = results.stream().limit(5)
                .map(RetrievalResult::semanticScore).collect(Collectors.toList());
        return writeJson(list);
    }

    /** top_candidates：取融合排名前 3 的候选（含余弦分），便于分析「对的块在不在候选里」。 */
    private String toTopCandidatesJson(List<RetrievalResult> results) {
        int n = Math.min(3, results.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RetrievalResult r = results.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", r.rank());
            m.put("documentId", r.documentId());
            m.put("seq", r.chunkIndex());
            m.put("semanticScore", r.semanticScore());
            m.put("source", r.source().name());
            list.add(m);
        }
        return writeJson(list);
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // 仅捕获 Jackson 真正会抛的序列化异常（§7.3 规则11：捕获具体异常，不 catch(Exception) 一把抓）
            log.warn("RagQuery json serialize failed: {}", e.getMessage());
            return "[]";
        }
    }

    // ===================== 内部载体 =====================

    /** Small-to-Big 拼好的上下文块（命中块 seq 为中心，前后 expand 块拼接）。 */
    private record ContextBlock(String documentId, int seq, double semanticScore, String context) {
    }

    // ===================== 请求 / 响应 DTO =====================

    /** 问答请求（§3.5 强类型）。 */
    public record RagQueryRequest(
            String query,
            List<ChatMessage> history,
            List<Long> mountedKbIds,
            Long agentId,
            RagConfig ragConfig,
            ProviderConfig providerConfig,
            /** 多库联合检索时用于日志的代表 kb（可空；为空则用首个挂载库）。 */
            Long primaryKbId) {
    }

    /** 问答结果。 */
    public record RagAnswer(
            boolean refused,
            String answer,
            RetrievalLog.RefusalReason refusalReason,
            List<SourceRef> sources,
            double topScore,
            double threshold,
            /** 是否由意图网关拦截（GREETING/IDENTITY/MEANINGLESS/TOOL），用于区分「友好响应」与「真实检索结果/拒答」。 */
            boolean intentFiltered) {

        /** 拒答结果构造（知识库确实查不到，intentFiltered=false）。 */
        public static RagAnswer refuse(RetrievalLog.RefusalReason reason, double threshold) {
            return new RagAnswer(true, REFUSAL_MESSAGE, reason, List.of(), 0.0, threshold, false);
        }

        /** 意图网关拦截后的友好响应（不检索、不调 LLM、不写 RetrievalLog，intentFiltered=true）。 */
        public static RagAnswer canned(String message) {
            return new RagAnswer(false, message, null, List.of(), 0.0, 0.0, true);
        }
    }

    /** 溯源引用（对应答案里的 [来源i]）。 */
    public record SourceRef(int index, String documentId, int seq, String title) {
    }
}
