package com.hify.hify.knowledge.dto;

import com.hify.hify.knowledge.entity.Document.SourceType;

/**
 * 文档上传请求（M5 T3）。
 *
 * <p>大白话：告诉系统要往哪个知识库（kbId）上传什么——是文件（FILE+filename）、网页（URL+sourceUrl）还是直接文本（TEXT+content）。
 */
public record KnowledgeBaseUploadRequest(
        /** 目标知识库 id（knowledge_base.id）。 */
        Long kbId,
        /** 来源类型 FILE/URL/TEXT。 */
        SourceType type,
        /** 文件名（FILE 时必填，用于后缀校验）。 */
        String filename,
        /** 文档标题（可空，默认取文件名）。 */
        String title,
        /** 文本内容（TEXT 时必填；FILE/URL 时为解析后的正文）。 */
        String content,
        /** 来源 URL（URL 时必填）。 */
        String sourceUrl) {
}
