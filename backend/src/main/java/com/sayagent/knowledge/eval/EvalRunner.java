package com.sayagent.knowledge.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.knowledge.config.RagConfig;
import com.sayagent.knowledge.config.RagProperties;
import com.sayagent.knowledge.entity.EvalDataset;
import com.sayagent.knowledge.entity.KnowledgeBase;
import com.sayagent.knowledge.repository.EvalDatasetRepository;
import com.sayagent.knowledge.retriever.RetrievalPort;
import com.sayagent.knowledge.retriever.RetrievalResult;
import com.sayagent.knowledge.service.KbAccessGuard;
import com.sayagent.knowledge.service.QueryRewriter;
import com.sayagent.knowledge.service.RagQueryService;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.route.ProviderRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题集打分 + 门禁（K10）。
 *
 * <p>大白话：拿一套「标准问答对」给知识库做"考试"——逐题跑真实 RAG 问答（K5 全部逻辑复用），
 * 再用大模型当裁判给「答案有没有编造 / 有没有答到问题」打分（RAGAS 的 Faithfulness / AnswerRelevancy），
 * 最后汇总结出 Recall@5 / MRR / NDCG / ContextPrecision-Recall / 拒答准确率 / 幻觉率 / P95 延迟，
 * 没达基线（需求 §9.2 / §9.4）就判门禁不通过、不许上线。
 *
 * <p>设计要点：
 * <ul>
 *   <li>评测集来源：优先 {@code eval_dataset} 表（按 kbId 收窄，§6.4 父维度隔离），为空时回退 classpath 的
 *       {@code rag/eval/<kbId>.json} 或 {@code rag/eval/default.json}（零 Flyway 迁移，种子即文件）。</li>
 *   <li>检索质量相关性判定：题集 {@code keywords} 命中检索块即视为相关（无独立"期望文档 id"列，用关键词对齐原文 E3）。</li>
 *   <li>忠实度/相关度用 LLM-judge（走 {@link ProviderRouter}，秘钥不出知识库模块），解析失败保守记 0 并打 WARN。</li>
 *   <li>纯指标公式在 {@link EvalMetrics}，本类只做编排，不重复数学逻辑，便于单测锁死公式。</li>
 * </ul>
 */
@Service
@Slf4j
public class EvalRunner {

    private static final Pattern JSON_OBJ = Pattern.compile("\\{[^{}]*\\}");
    private static final int JUDGE_TOP_K = 5;
    private static final int JUDGE_CONTEXT_MAX = 4000;

    private final RagQueryService ragQueryService;
    private final RetrievalPort retrievalPort;
    private final QueryRewriter queryRewriter;
    private final ProviderRouter providerRouter;
    private final EvalDatasetRepository evalDatasetRepository;
    private final KbAccessGuard accessGuard;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvalRunner(RagQueryService ragQueryService,
                      RetrievalPort retrievalPort,
                      QueryRewriter queryRewriter,
                      ProviderRouter providerRouter,
                      EvalDatasetRepository evalDatasetRepository,
                      KbAccessGuard accessGuard,
                      RagProperties ragProperties) {
        this.ragQueryService = ragQueryService;
        this.retrievalPort = retrievalPort;
        this.queryRewriter = queryRewriter;
        this.providerRouter = providerRouter;
        this.evalDatasetRepository = evalDatasetRepository;
        this.accessGuard = accessGuard;
        this.ragProperties = ragProperties;
    }

    /**
     * 按库跑完整评测（从 DB / JSON 加载题集）。
     */
    public EvalReport run(Long kbId) {
        return run(kbId, loadCases(kbId));
    }

    /**
     * 用给定的题集跑评测（不触达资源加载，便于单测注入）。
     */
    public EvalReport run(Long kbId, List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return EvalReport.empty();
        }
        RagConfig ragConfig = resolveConfig(kbId);
        List<EvalItemResult> items = new ArrayList<>();
        List<Double> recallList = new ArrayList<>();
        List<Double> mrrList = new ArrayList<>();
        List<Double> ndcgList = new ArrayList<>();
        List<Double> cpList = new ArrayList<>();
        List<Double> crList = new ArrayList<>();
        List<Double> faithList = new ArrayList<>();
        List<Double> arList = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        int total = cases.size();
        int answered = 0;
        int refused = 0;
        int wrongRefused = 0;
        int shouldRejectTotal = 0;
        int rejectionOk = 0;

        for (EvalCase c : cases) {
            long start = System.nanoTime();
            boolean shouldReject = Boolean.TRUE.equals(c.shouldReject());
            String rewritten = queryRewriter.rewrite(c.question(), null);
            RagQueryService.RagAnswer answer = ragQueryService.query(new RagQueryService.RagQueryRequest(
                    c.question(), null, List.of(kbId), null, ragConfig, null, kbId));
            boolean isRefused = answer.refused();

            List<RetrievalResult> retrieved = retrievalPort.retrieveHybrid(rewritten, List.of(kbId), ragConfig);
            List<String> keywords = parseKeywords(c.keywords());

            double recall = 0.0, mrr = 0.0, ndcg = 0.0, cp = 0.0, cr = 0.0;
            if (!shouldReject) {
                List<Integer> rel = new ArrayList<>();
                Set<String> covered = new HashSet<>();
                int relevantInTop5 = 0;
                int totalRelevant = 0;
                int firstRank = 0;
                int k = 0;
                for (RetrievalResult r : retrieved) {
                    k++;
                    boolean relChunk = containsAnyKeyword(r.content(), keywords);
                    rel.add(relChunk ? 1 : 0);
                    if (relChunk) {
                        totalRelevant++;
                        if (firstRank == 0) {
                            firstRank = r.rank();
                        }
                        if (k <= JUDGE_TOP_K) {
                            relevantInTop5++;
                        }
                    }
                    if (k <= JUDGE_TOP_K && r.content() != null) {
                        String lower = r.content().toLowerCase();
                        for (String kw : keywords) {
                            if (!kw.isEmpty() && lower.contains(kw)) {
                                covered.add(kw);
                            }
                        }
                    }
                }
                recall = EvalMetrics.recallAtK(relevantInTop5, totalRelevant);
                mrr = EvalMetrics.mrr(firstRank);
                ndcg = EvalMetrics.ndcgAtK(rel, JUDGE_TOP_K);
                cp = EvalMetrics.contextPrecision(relevantInTop5, JUDGE_TOP_K);
                cr = EvalMetrics.contextRecall(covered.size(), keywords.size());
                recallList.add(recall);
                mrrList.add(mrr);
                ndcgList.add(ndcg);
                cpList.add(cp);
                crList.add(cr);
            }

            double faithfulness = 0.0;
            double answerRelevancy = 0.0;
            if (!isRefused && !shouldReject) {
                String context = joinContext(retrieved, JUDGE_TOP_K);
                String judgeJson = judge(rewritten, context, answer.answer());
                faithfulness = parseScore(judgeJson, "faithfulness");
                answerRelevancy = parseScore(judgeJson, "answer_relevancy");
                faithList.add(faithfulness);
                arList.add(answerRelevancy);
            }

            long cost = (System.nanoTime() - start) / 1_000_000;
            latencies.add(cost);

            if (isRefused) {
                refused++;
            } else {
                answered++;
            }
            if (shouldReject) {
                shouldRejectTotal++;
                if (isRefused) {
                    rejectionOk++;
                }
            } else if (isRefused) {
                wrongRefused++;
            }

            items.add(new EvalItemResult(c.question(), c.type(), shouldReject, isRefused,
                    recall, mrr, ndcg, cp, cr, faithfulness, answerRelevancy, cost));
        }

        double recallMean = EvalMetrics.mean(recallList);
        double mrrMean = EvalMetrics.mean(mrrList);
        double ndcgMean = EvalMetrics.mean(ndcgList);
        double cpMean = EvalMetrics.mean(cpList);
        double crMean = EvalMetrics.mean(crList);
        double faithMean = EvalMetrics.mean(faithList);
        double arMean = EvalMetrics.mean(arList);
        int inScope = total - shouldRejectTotal;
        double wrongRefusalRate = inScope == 0 ? 0.0 : (double) wrongRefused / inScope;
        double rejectionAccuracy = shouldRejectTotal == 0 ? 0.0 : (double) rejectionOk / shouldRejectTotal;
        double hallucinationRate = 1.0 - faithMean;
        long p95 = EvalMetrics.p95(latencies);
        boolean gate = EvalMetrics.gate(recallMean, faithMean, wrongRefusalRate, hallucinationRate, p95);

        return new EvalReport(total, answered, refused, recallMean, mrrMean, ndcgMean,
                cpMean, crMean, faithMean, arMean, wrongRefusalRate, rejectionAccuracy,
                hallucinationRate, p95, gate, items);
    }

    // ===================== 题集加载 =====================

    private List<EvalCase> loadCases(Long kbId) {
        List<EvalDataset> db = evalDatasetRepository.findByKbId(kbId);
        if (db != null && !db.isEmpty()) {
            return db.stream().map(this::toCase).toList();
        }
        return loadFromResources(kbId);
    }

    private EvalCase toCase(EvalDataset ds) {
        return new EvalCase(ds.getQuestion(), ds.getType(), ds.getKeywords(), ds.getExpected(), ds.getShouldReject());
    }

    private List<EvalCase> loadFromResources(Long kbId) {
        for (String path : List.of("rag/eval/" + kbId + ".json", "rag/eval/default.json")) {
            ClassPathResource res = new ClassPathResource(path);
            if (!res.exists()) {
                continue;
            }
            try {
                EvalCase[] arr = objectMapper.readValue(res.getInputStream(), EvalCase[].class);
                if (arr != null && arr.length > 0) {
                    return Arrays.asList(arr);
                }
            } catch (IOException e) {
                log.warn("eval load resource {} failed: {}", path, e.getMessage());
            }
        }
        return List.of();
    }

    private RagConfig resolveConfig(Long kbId) {
        KnowledgeBase kb = accessGuard.requireAccessible(kbId);
        return kb.getEffectiveConfig(RagConfig.fromGlobal(ragProperties));
    }

    // ===================== 相关性 / 上下文 =====================

    private List<String> parseKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keywords.split("[,，、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }

    private boolean containsAnyKeyword(String content, List<String> keywords) {
        if (content == null || keywords.isEmpty()) {
            return false;
        }
        String lower = content.toLowerCase();
        for (String kw : keywords) {
            if (!kw.isEmpty() && lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String joinContext(List<RetrievalResult> retrieved, int k) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (RetrievalResult r : retrieved) {
            if (n >= k) {
                break;
            }
            if (r.content() != null) {
                sb.append(r.content()).append("\n");
            }
            n++;
        }
        String ctx = sb.toString();
        return ctx.length() > JUDGE_CONTEXT_MAX ? ctx.substring(0, JUDGE_CONTEXT_MAX) : ctx;
    }

    // ===================== LLM 裁判 =====================

    private String judge(String question, String context, String answer) {
        String user = "【问题】\n" + question
                + "\n\n【资料】\n" + context
                + "\n\n【回答】\n" + (answer == null ? "" : answer)
                + "\n\n只输出 JSON：{\"faithfulness\": <0到1的浮点>, \"answer_relevancy\": <0到1的浮点>}。不要输出其它内容。";
        try {
            LlmResponse resp = providerRouter.route(List.of(
                    new ChatMessage("system", "你是严格的 RAG 评测裁判。只依据【资料】判断【回答】是否完全由资料支撑、"
                            + "是否真正回答了【问题】。不得编造评分，只输出要求的 JSON。"),
                    new ChatMessage("user", user)));
            return resp != null && resp.getContent() != null ? resp.getContent() : "{}";
        } catch (Exception e) {
            log.warn("eval judge llm call failed: {}", e.getMessage());
            return "{}";
        }
    }

    private double parseScore(String json, String field) {
        if (json == null || json.isBlank()) {
            return 0.0;
        }
        Matcher m = JSON_OBJ.matcher(json);
        if (!m.find()) {
            return 0.0;
        }
        try {
            JsonNode node = objectMapper.readTree(m.group());
            double v = node.path(field).asDouble(Double.NaN);
            if (Double.isNaN(v)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, v));
        } catch (JsonProcessingException e) {
            log.warn("eval judge parse field={} failed: {}", field, e.getMessage());
            return 0.0;
        }
    }

    // ===================== 载体 =====================

    /** 单条评测题。 */
    public record EvalCase(String question, String type, String keywords, String expected, Boolean shouldReject) {
    }

    /** 单题评测结果。 */
    public record EvalItemResult(String question, String type, boolean shouldReject, boolean refused,
                                 double recallAt5, double mrr, double ndcg,
                                 double contextPrecision, double contextRecall,
                                 double faithfulness, double answerRelevancy, long latencyMs) {
    }

    /** 整份评测报告 + 门禁结论。 */
    public record EvalReport(int total, int answered, int refused,
                             double recallAt5Mean, double mrrMean, double ndcgMean,
                             double contextPrecisionMean, double contextRecallMean,
                             double faithfulnessMean, double answerRelevancyMean,
                             double wrongRefusalRate, double rejectionAccuracy,
                             double hallucinationRate, long p95LatencyMs,
                             boolean gate, List<EvalItemResult> items) {

        public static EvalReport empty() {
            return new EvalReport(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0L, false, List.of());
        }

        /** 给人看的报告文本（便于上线前体检打印）。 */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 知识库评测报告 ===\n");
            sb.append(String.format("总题数=%d 已答=%d 拒答=%d%n", total, answered, refused));
            sb.append(String.format("Recall@5=%.3f MRR=%.3f NDCG@5=%.3f%n", recallAt5Mean, mrrMean, ndcgMean));
            sb.append(String.format("ContextPrecision=%.3f ContextRecall=%.3f%n", contextPrecisionMean, contextRecallMean));
            sb.append(String.format("Faithfulness=%.3f AnswerRelevancy=%.3f%n", faithfulnessMean, answerRelevancyMean));
            sb.append(String.format("错误拒答率=%.3f 拒答准确率=%.3f 幻觉率=%.3f%n",
                    wrongRefusalRate, rejectionAccuracy, hallucinationRate));
            sb.append(String.format("P95延迟=%dms%n", p95LatencyMs));
            sb.append(gate ? "门禁：✅ 通过（可上线）" : "门禁：❌ 未通过（不许上线）").append("\n");
            return sb.toString();
        }
    }
}
