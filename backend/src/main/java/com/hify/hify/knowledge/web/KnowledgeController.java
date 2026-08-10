package com.hify.hify.knowledge.web;

import com.hify.hify.common.Result;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.dto.MountRequest;
import com.hify.hify.knowledge.eval.EvalRunner;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.service.IndexingJobService;
import com.hify.hify.knowledge.service.KbAdminService;
import com.hify.hify.knowledge.service.KbQaService;
import com.hify.hify.knowledge.service.KnowledgeService;
import com.hify.hify.knowledge.service.MountService;
import com.hify.hify.knowledge.web.DocumentSummaryVO;
import com.hify.hify.knowledge.web.IndexingJobVO;
import com.hify.hify.knowledge.web.KnowledgeBaseUpdateRequest;
import com.hify.hify.knowledge.web.ChunkVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * 知识库控制层（M5/T5 + K7 挂载）。
 *
 * <p>大白话：这一层极薄——只收请求、做参数校验、调 service、把结果装进统一响应盒子 {@link Result}，
 * 不碰任何向量/LLM 细节（解耦纪律 §3.2：Controller 只做参数校验与响应封装）。
 *
 * <p>K7 权限模型收敛为单一入口：知识库的「挂载关系」由本控制器管理（{@code /{agentId}/kb-links}），
 * 旧的「按人/角色授权」双权限体系（{@code /{kbId}/access}）已退役，避免歧义（§3.5）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final MountService mountService;
    private final KbAdminService kbAdminService;
    private final KbQaService kbQaService;
    private final IndexingJobService indexingJobService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               MountService mountService,
                               KbAdminService kbAdminService,
                               KbQaService kbQaService,
                               IndexingJobService indexingJobService) {
        this.knowledgeService = knowledgeService;
        this.mountService = mountService;
        this.kbAdminService = kbAdminService;
        this.kbQaService = kbQaService;
        this.indexingJobService = indexingJobService;
    }

    /**
     * 上传知识库文档：复用 T3 的 {@code uploadDocument}，返回文档视图对象 DocumentVO。
     */
    @PostMapping("/upload")
    public Result<DocumentVO> upload(@Valid @RequestBody KnowledgeBaseUploadRequest req) {
        String documentId = knowledgeService.uploadDocument(req);
        return Result.ok(knowledgeService.getDocumentVO(documentId));
    }

    /**
     * 知识库检索：把问题转成向量后，按余弦相似度取 Top-k 片段。
     */
    @PostMapping("/retrieve")
    public Result<List<ChunkVO>> retrieve(@Valid @RequestBody RetrieveRequest req) {
        List<ChunkVO> chunks = knowledgeService.retrieve(req.kbId(), req.query(), req.topKOrDefault());
        return Result.ok(chunks);
    }

    /**
     * 挂载知识库到 Agent（仅 Agent 创建者/admin，§3.5 挂载权）。
     *
     * @return true=新建挂载；false=原本已挂载（幂等）
     */
    @PostMapping("/{agentId}/kb-links")
    public Result<Boolean> mount(@PathVariable Long agentId,
                                 @Valid @RequestBody MountRequest req) {
        boolean created = mountService.mount(agentId, req.kbId());
        return Result.ok(created);
    }

    /**
     * 建库（两步创建·第一步，K8）：返回空库，可随后再上传文档。
     */
    @PostMapping("/bases")
    public Result<KnowledgeBaseVO> createBase(@Valid @RequestBody KnowledgeBaseCreateRequest req) {
        return Result.ok(kbAdminService.createBase(req));
    }

    /**
     * 知识库列表（K8 keyset 游标分页 §6.4）：不传 lastId 取首页，传则翻下一页。
     */
    @GetMapping("/bases")
    public Result<PageVO<KnowledgeBaseVO>> listBases(
            @RequestParam(name = "lastId", required = false) Long lastId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return Result.ok(kbAdminService.listBases(lastId, limit));
    }

    /**
     * 批量上传文档（K8）：一次最多 10 个，每个共享同一 batch 异步索引；返回每个文档 id + 状态。
     */
    @PostMapping("/{kbId}/upload")
    public Result<UploadResponse> uploadBatch(@PathVariable Long kbId,
                                              @Valid @RequestBody List<KnowledgeBaseUploadItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上传列表不能为空");
        }
        if (items.size() > 10) {
            throw new BizException(ErrorCode.UPLOAD_TOO_MANY, "单次最多上传 10 个文件");
        }
        for (KnowledgeBaseUploadItem it : items) {
            if (it.content() != null && it.content().length() > 20 * 1024 * 1024) {
                throw new BizException(ErrorCode.FILE_TOO_LARGE, "单文件内容超过 20MB 上限");
            }
            if (it.type() == Document.SourceType.FILE
                    && (it.filename() == null || !isAllowedExtLocal(it.filename()))) {
                throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE);
            }
        }
        List<KnowledgeBaseUploadRequest> reqs = items.stream()
                .map(it -> new KnowledgeBaseUploadRequest(kbId, it.type(), it.filename(), it.title(), it.content(), it.sourceUrl(), null))
                .toList();
        List<String> docIds = knowledgeService.uploadDocuments(reqs);
        List<UploadResponse.UploadItemResult> results = docIds.stream()
                .map(id -> new UploadResponse.UploadItemResult(id, knowledgeService.getDocumentStatus(id)))
                .toList();
        return Result.ok(new UploadResponse(results));
    }

    /**
     * 二进制文件批量上传（PDF/DOCX/MD/TXT）：接收真实文件字节，后端用 Tika 解析后走索引流水线。
     *
     * <p>大白话：与 {@code /{kbId}/upload}（JSON 传文本）不同，这里是真·文件上传——
     * 前端把选中的文件（含 PDF/DOCX）原样以 multipart 二进制发来，后端解析成文本再入库，
     * 解决此前「只能传 txt/md、PDF/DOCX 读成乱码」的问题。
     */
    @PostMapping("/{kbId}/upload-files")
    public Result<UploadResponse> uploadFiles(@PathVariable Long kbId,
                                              @RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上传文件列表不能为空");
        }
        if (files.size() > 10) {
            throw new BizException(ErrorCode.UPLOAD_TOO_MANY, "单次最多上传 10 个文件");
        }
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (f.getSize() > 20L * 1024 * 1024) {
                throw new BizException(ErrorCode.FILE_TOO_LARGE, "单文件超过 20MB 上限：" + name);
            }
            if (name == null || !isAllowedExtLocal(name)) {
                throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE, name);
            }
        }
        List<String> docIds = knowledgeService.uploadFiles(kbId, files);
        List<UploadResponse.UploadItemResult> results = docIds.stream()
                .map(id -> new UploadResponse.UploadItemResult(id, knowledgeService.getDocumentStatus(id)))
                .toList();
        return Result.ok(new UploadResponse(results));
    }

    /**
     * 文档列表（K11 / K9 缺口①）：某知识库下的文档（keyset 游标分页），供文档管理页与「重新上传」按钮。
     */
    @GetMapping("/{kbId}/documents")
    public Result<PageVO<DocumentSummaryVO>> listDocuments(@PathVariable Long kbId,
                                                          @RequestParam(name = "lastId", required = false) Long lastId,
                                                          @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return Result.ok(knowledgeService.listDocuments(kbId, lastId, limit));
    }

    /**
     * 删除文档（K11 / K9 缺口①）：软删文档 + 清掉 PG 切片（孤儿 chunk 不再召回）。
     */
    @DeleteMapping("/{kbId}/documents/{documentId}")
    public Result<Void> deleteDocument(@PathVariable Long kbId, @PathVariable String documentId) {
        knowledgeService.deleteDocument(kbId, documentId);
        return Result.ok();
    }

    /**
     * 查看/下载源文档：FILE 且有落盘字节 → 流式返回原始文件（PDF 内联预览，其余附件下载）；
     * 无二进制源（TEXT / 旧上传）→ 回退返回 rawContent 文本。供前端「查看源文档」按钮。
     */
    @GetMapping("/{kbId}/documents/{documentId}/source")
    public ResponseEntity<Resource> getDocumentSource(@PathVariable Long kbId, @PathVariable String documentId) {
        return knowledgeService.getSource(kbId, documentId);
    }

    /**
     * 列出某文档入库后的全部切片（按 seq 升序），供前端「切片预览」面板直接查看被切成哪几段。
     */
    @GetMapping("/{kbId}/documents/{documentId}/chunks")
    public Result<List<ChunkVO>> getDocumentChunks(@PathVariable Long kbId, @PathVariable String documentId) {
        return Result.ok(knowledgeService.getDocumentChunks(kbId, documentId));
    }

    /**
     * 更新知识库（K11 收口 K8 缺口②）：仅覆盖请求中非空的字段。
     */
    @PutMapping("/bases/{id}")
    public Result<KnowledgeBaseVO> updateBase(@PathVariable Long id,
                                              @Valid @RequestBody KnowledgeBaseUpdateRequest req) {
        return Result.ok(kbAdminService.updateBase(id, req));
    }

    /**
     * 删除知识库（K11 收口 K8 缺口②）：软删库 + 级联软删其下文档 + 清 PG 切片。
     */
    @DeleteMapping("/bases/{id}")
    public Result<Void> deleteBase(@PathVariable Long id) {
        kbAdminService.deleteBase(id);
        return Result.ok();
    }

    /**
     * 查询某索引任务状态（K11 / K9 缺口③）：前端轮询上传/重传进度用。
     */
    @GetMapping("/{kbId}/indexing-jobs/{jobId}")
    public Result<IndexingJobVO> getIndexingJob(@PathVariable Long kbId, @PathVariable Long jobId) {
        return Result.ok(indexingJobService.getJob(jobId));
    }

    /**
     * 重试一条 FAILED 索引任务（K11）：从失败节点续跑（不自动重试，防死循环）。
     */
    @PostMapping("/{kbId}/indexing-jobs/{jobId}/retry")
    public Result<Void> retryIndexingJob(@PathVariable Long kbId, @PathVariable Long jobId) {
        indexingJobService.retry(jobId);
        return Result.ok();
    }

    /**
     * 批量重试某批次内所有 FAILED 任务（K11），返回实际被重试的任务数。
     */
    @PostMapping("/{kbId}/indexing-jobs/retry-batch")
    public Result<Integer> retryIndexingBatch(@PathVariable Long kbId,
                                              @RequestParam(name = "batchId") String batchId) {
        return Result.ok(indexingJobService.retryBatch(batchId));
    }

    /**
     * 知识库问答（K8，编排 K5）：返回答案 + 来源；阈值不达标由 K5 拒答（绝不瞎编）。
     */
    @PostMapping("/{kbId}/ask")
    public Result<AskResponse> ask(@PathVariable Long kbId, @Valid @RequestBody AskRequest req) {
        return Result.ok(kbQaService.ask(kbId, req.query(), req.history()));
    }

    /**
     * 知识库体检（K8）：返回基础健康 / 命中质量 / 响应速度三项指标。
     */
    @GetMapping("/{kbId}/health")
    public Result<HealthVO> health(@PathVariable Long kbId) {
        return Result.ok(kbAdminService.health(kbId));
    }

    /**
     * 试问台（K8）：仅预览「该问题能命中哪些片段、是否达阈值」，不调 LLM 生成答案。
     */
    @PostMapping("/{kbId}/probe")
    public Result<ProbeResultVO> probe(@PathVariable Long kbId, @Valid @RequestBody ProbeRequest req) {
        return Result.ok(kbQaService.probe(kbId, req.query()));
    }

    /**
     * 题集打分（K8）：逐题跑真实问答，汇总命中率 / 拒答率 / 平均耗时。
     */
    @PostMapping("/{kbId}/eval")
    public Result<EvalResultVO> eval(@PathVariable Long kbId, @Valid @RequestBody EvalRequest req) {
        return Result.ok(kbQaService.eval(kbId, req.questions()));
    }

    /**
     * 题集全量打分 + 门禁（K10）：跑完整 RAGAS 评分并给出门禁结论，未达标不许上线。
     *
     * <p>大白话：上线前用标准题集给知识库"考试"——Recall@5 / MRR / NDCG / 忠实度 / 相关度 / 拒答准确率 /
     * 幻觉率 / P95 延迟一应俱全，{@code gate=false} 即代表未达基线（需求 §9.4），须先补齐再上线。
     * 题集来源：库评测集（{@code eval_dataset} 表，按 kbId 收窄，§6.4）优先，为空回退 classpath
     * {@code rag/eval/<kbId>.json} 或 {@code rag/eval/default.json}（零 Flyway 迁移种子）。
     */
    @PostMapping("/{kbId}/eval-run")
    public Result<EvalRunner.EvalReport> evalRun(@PathVariable Long kbId) {
        return Result.ok(kbQaService.runFullEval(kbId));
    }

    /**
     * 列出某 Agent 已挂载（未软删）的知识库（检索隔离维度，供前端/检索使用）。
     */
    @GetMapping("/{agentId}/kb-links")
    public Result<List<KbLinkVO>> listMounted(@PathVariable Long agentId) {
        return Result.ok(mountService.getMountedKbIds(agentId).stream().map(KbLinkVO::new).toList());
    }

    /**
     * 卸载某 Agent 的某知识库（仅 Agent 创建者/admin，软删）。
     */
    @DeleteMapping("/{agentId}/kb-links/{kbId}")
    public Result<Void> unmount(@PathVariable Long agentId, @PathVariable Long kbId) {
        mountService.unmount(agentId, kbId);
        return Result.ok();
    }

    /** 上传白名单校验（与 KnowledgeService.ALLOWED_EXT 同源，FILE 必须有合法后缀，含老 .doc）。 */
    private static boolean isAllowedExtLocal(String filename) {
        String lower = filename.toLowerCase();
        return List.of(".txt", ".md", ".pdf", ".docx", ".doc").stream().anyMatch(lower::endsWith);
    }
}
