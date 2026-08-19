package com.sayagent.knowledge.web;

import com.sayagent.knowledge.entity.Document.SourceType;

import jakarta.validation.constraints.NotNull;

/**
 * 批量上传的单个文档条目（K8 {@code POST /{kbId}/upload}）。
 *
 * <p>大白话：一次可以传最多 10 个文件，每个条目描述「这是什么类型的来源、叫什么名、内容是什么」。
 * 知识库 id 由路径参数 {@code kbId} 统一注入（避免每个条目重复填），所以这里不带 kbId。
 */
public record KnowledgeBaseUploadItem(

        @NotNull(message = "来源类型不能为空")
        SourceType type,

        /** 文件名（FILE 时必填，用于后缀白名单校验）。 */
        String filename,

        /** 文档标题（可空，默认取文件名）。 */
        String title,

        /** 文本内容（TEXT 必填；FILE/URL 时为解析后的正文）。 */
        String content,

        /** 来源 URL（URL 时必填）。 */
        String sourceUrl
) {
}
