package com.hify.hify.knowledge.service;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.config.RagProperties;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.eval.EvalRunner;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.knowledge.web.AskResponse;
import com.hify.hify.knowledge.web.EvalRequest;
import com.hify.hify.knowledge.web.EvalResultVO;
import com.hify.hify.knowledge.web.ProbeResultVO;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.route.ProviderRouter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库「使用面」服务（K8）：问答、试问台、题集打分。
 *
 * <p>大白话：这一类只管「拿库去回答问题」——正式提问（{@link #ask}）、上线前试问看能捞到啥
 * （{@link #probe}）、批量跑题集看整体表现（{@link #eval}）。库本身的建/列/体检在
 * {@link KbAdminService}，往库里塞文档在 {@link KnowledgeService}（§3.7 单一职责拆分）。
 *
 * <p>本类只做「编排」：拒答阈值、查询改写、Small-to-Big 扩展、来源溯源、检索日志落库
 * 全部由 K5 的 {@link RagQueryService} 负责，这里不重复实现。
 */
@Service
public class KbQaService {

    private final RetrievalPort retrievalPort;
    private final RagQueryService ragQueryService;
    private final RagProperties ragProperties;
    private final ProviderRouter providerRouter;
    private final RetrievalLogRepository retrievalLogRepository;
    private final KbAccessGuard accessGuard;
    private final EvalRunner evalRunner;

    public KbQaService(RetrievalPort retrievalPort,
                       RagQueryService ragQueryService,
                       RagProperties ragProperties,
                       ProviderRouter providerRouter,
                       RetrievalLogRepository retrievalLogRepository,
                       KbAccessGuard accessGuard,
                       EvalRunner evalRunner) {
        this.retrievalPort = retrievalPort;
        this.ragQueryService = ragQueryService;
        this.ragProperties = ragProperties;
        this.providerRouter = providerRouter;
        this.retrievalLogRepository = retrievalLogRepository;
        this.accessGuard = accessGuard;
        this.evalRunner = evalRunner;
    }

    /**
     * 知识库问答（K8，编排 K5 {@link RagQueryService}）：返回带来源的答案。
     *
     * <p>大白话：把「问题 + 这个库 + 这个库的生效参数 + 默认聊天模型」打包丢给 K5，
     * K5 捞不到足够相关的内容就直接拒答（宁可不答也不瞎编），答了就带上原文出处。
     */
    public AskResponse ask(Long kbId, String query, List<ChatMessage> history) {
        KnowledgeBase kb = accessGuard.requireAccessible(kbId);
        RagQueryService.RagAnswer answer = ragQueryService.query(new RagQueryService.RagQueryRequest(
                query, history, List.of(kbId), null, effectiveConfig(kb), defaultProvider(), kbId));
        List<AskResponse.AskSource> sources = answer.sources() == null ? List.of() :
                answer.sources().stream()
                        .map(s -> new AskResponse.AskSource(s.index(), s.documentId(), s.seq(), s.title()))
                        .toList();
        return new AskResponse(answer.refused(), answer.answer(),
                answer.refusalReason() == null ? null : answer.refusalReason().name(),
                answer.topScore(), answer.threshold(), sources);
    }

    /**
     * 试问台（K8）：只跑一遍混合检索预览，不调 LLM 生成答案。
     *
     * <p>大白话：上线前想知道「这个问题到底能不能捞到东西」，这里给你看候选片段和分数，
     * 省掉一次模型调用的钱和等待。
     */
    public ProbeResultVO probe(Long kbId, String query) {
        KnowledgeBase kb = accessGuard.requireAccessible(kbId);
        RagConfig ragConfig = effectiveConfig(kb);
        List<RetrievalResult> results = retrievalPort.retrieveHybrid(query, List.of(kbId), ragConfig);
        double threshold = ragConfig.scoreThreshold();
        double topScore = results.stream().mapToDouble(RetrievalResult::semanticScore).max().orElse(0.0);
        boolean hit = !results.isEmpty() && topScore >= threshold;
        List<ProbeResultVO.ProbeCandidate> candidates = results.stream().limit(5)
                .map(r -> new ProbeResultVO.ProbeCandidate(
                        r.documentId(), r.chunkIndex(), r.semanticScore(), snippet(r.content())))
                .toList();
        return new ProbeResultVO(hit, topScore, threshold, candidates);
    }

    /**
     * 题集打分（K8 轻量版）：逐题跑真实问答（含 LLM 生成），汇总命中率 / 拒答率 / 平均耗时。
     *
     * <p>忠实度（faithfulness）需要评测模型裁判，K8 不在此重复接入——完整的 RAGAS
     * （Faithfulness / AnswerRelevancy）+ Recall@5 / MRR / NDCG + 门禁已由 K10 的
     * {@code EvalRunner} 实现，并已在 {@code POST /{kbId}/eval-run}（{@link #runFullEval}）落地：
     * 该端点跑完整 RAGAS 评分并给出门禁结论（未达标不许上线）。本轻量 {@code eval} 方法仅作快速汇总，
     * faithfulness 恒为 {@code N/A（需评测模型）}，不参与门禁判定。
     */
    public EvalResultVO eval(Long kbId, List<EvalRequest.EvalQuestion> questions) {
        KnowledgeBase kb = accessGuard.requireAccessible(kbId);
        RagConfig ragConfig = effectiveConfig(kb);
        ProviderConfig providerConfig = defaultProvider();

        int total = questions.size();
        int answered = 0;
        int refused = 0;
        double sumTop = 0.0;
        List<EvalResultVO.EvalItem> items = new ArrayList<>(total);
        for (EvalRequest.EvalQuestion q : questions) {
            RagQueryService.RagAnswer answer = ragQueryService.query(new RagQueryService.RagQueryRequest(
                    q.question(), null, List.of(kbId), null, ragConfig, providerConfig, kbId));
            if (answer.refused()) {
                refused++;
            } else {
                answered++;
            }
            sumTop += answer.topScore();
            items.add(new EvalResultVO.EvalItem(q.question(), answer.refused(), answer.topScore(),
                    answer.sources() == null ? 0 : answer.sources().size(), snippet(answer.answer())));
        }
        double hitRate = total == 0 ? 0.0 : (double) answered / total;
        double avgTopScore = total == 0 ? 0.0 : sumTop / total;
        return new EvalResultVO(total, answered, refused, hitRate, avgTopScore, avgCostMs(kbId),
                "N/A（需评测模型）", items);
    }

    /**
     * 题集全量打分 + 门禁（K10）：委托 {@link EvalRunner} 跑完整 RAGAS + 门禁。
     *
     * <p>大白话：这才是"考试真卷"——从库评测集（{@code eval_dataset} 表，按 kbId 收窄，§6.4）/ 种子 JSON
     * 加载题集，逐题跑真实 RAG 问答（复用 K5 全部逻辑），再让 LLM 当裁判打忠实度 / 相关度，
     * 最后汇总结出 Recall@5 / MRR / NDCG / ContextPrecision-Recall / 拒答准确率 / 幻觉率 / P95 延迟，
     * 并由 {@link EvalMetrics#gate} 给出门禁结论（未达基线不许上线，对应需求 §9.4）。
     *
     * <p>对应接口 {@code POST /{kbId}/eval-run}。题集若为空（库无评测集且种子缺失）返回
     * {@link EvalRunner.EvalReport#empty()}（门禁为 false，明确提示"无题集不可上线"）。
     */
    public EvalRunner.EvalReport runFullEval(Long kbId) {
        return evalRunner.run(kbId);
    }

    /** 库级参数覆盖全局兜底（K2：库没配就吃 application.yml 的默认值）。 */
    private RagConfig effectiveConfig(KnowledgeBase kb) {
        return kb.getEffectiveConfig(RagConfig.fromGlobal(ragProperties));
    }

    /** 默认聊天模型配置（走 T2 ProviderRouter，不在知识库模块里硬编码任何秘钥）。 */
    private ProviderConfig defaultProvider() {
        return providerRouter.getDefaultChatConfig();
    }

    /** 取最近一批检索日志的平均耗时，作为题集打分的响应速度参考。 */
    private double avgCostMs(Long kbId) {
        List<RetrievalLog> logs = retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(kbId);
        double sum = 0.0;
        int n = 0;
        for (RetrievalLog r : logs) {
            if (r.getCostMs() != null) {
                sum += r.getCostMs();
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /** 长文本截断为预览片段（前端列表只需看个大概，避免响应体过大）。 */
    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 200 ? content.substring(0, 200) + "…" : content;
    }
}
