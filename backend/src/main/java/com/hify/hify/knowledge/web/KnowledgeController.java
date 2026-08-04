package com.hify.hify.knowledge.web;

import com.hify.hify.common.Result;
import com.hify.hify.knowledge.dto.KbAccessRequest;
import com.hify.hify.knowledge.dto.KnowledgeBaseUploadRequest;
import com.hify.hify.knowledge.service.KnowledgeService;
import com.hify.hify.knowledge.web.KbAccessVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库控制层（M5/T5）。
 *
 * <p>大白话：这一层极薄——只收请求、做参数校验、调 service、把结果装进统一响应盒子 {@link Result}，
 * 不碰任何向量/LLM 细节（解耦纪律 §3.2：Controller 只做参数校验与响应封装）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
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
     * 分配知识库访问权（仅 ADMIN）：按角色(ROLE)或具体人(USER)授权（RBAC，M5 整改）。
     */
    @PostMapping("/{kbId}/access")
    public Result<KbAccessVO> grantAccess(@PathVariable Long kbId,
                                         @Valid @RequestBody KbAccessRequest req) {
        return Result.ok(knowledgeService.grantAccess(kbId, req.targetType(), req.targetId()));
    }

    /**
     * 列出某知识库的访问授权（仅 ADMIN）。
     */
    @GetMapping("/{kbId}/access")
    public Result<List<KbAccessVO>> listAccess(@PathVariable Long kbId) {
        return Result.ok(knowledgeService.listAccess(kbId));
    }

    /**
     * 撤销某知识库的访问授权（仅 ADMIN）。
     */
    @DeleteMapping("/{kbId}/access/{accessId}")
    public Result<Void> revokeAccess(@PathVariable Long kbId, @PathVariable Long accessId) {
        knowledgeService.revokeAccess(kbId, accessId);
        return Result.ok();
    }
}
