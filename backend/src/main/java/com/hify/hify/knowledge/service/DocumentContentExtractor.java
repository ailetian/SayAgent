package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import com.hify.hify.knowledge.entity.Document;

import org.springframework.stereotype.Component;

/**
 * 生产用「取原文」实现（K6）。
 *
 * <p>大白话：当前上传入口（{@code KnowledgeBaseUploadRequest} / M5 T3）携带的是"已解析好的文本"
 * （TEXT 直粘 / URL 抓正文 / FILE 提供内容），所以这里直接读 {@code document.rawContent} 即可，
 * 跳过真实文件 IO。若什么文本都没带（例如只传了文件名没传内容的 FILE），则明确抛
 * {@link ErrorCode#FORMAT_CORRUPTED}，让 PARSE 阶段把"缺内容"暴露出来，而不是静默索引出 0 块空文档。
 *
 * <p>后续 K8/K9 接 MultipartFile 真实字节上传时，可在此扩展：FILE 且 rawContent 为空 →
 * 按扩展名路由 {@code DocumentParsers} 用 Tika 解析 PDF/DOCX（那时"加密 PDF/扫描件"细分错误码才真正触发）。
 * 这样索引状态机的 PARSE 失败路径在单测里已用桩验证，生产只是换个取文本的实现。
 */
@Component
public class DocumentContentExtractor implements ContentExtractor {

    @Override
    public String extract(Document doc, String filename) {
        if (doc.getRawContent() != null && !doc.getRawContent().isBlank()) {
            return doc.getRawContent();
        }
        throw new BizException(ErrorCode.FORMAT_CORRUPTED,
                "未提供可索引的文本内容（FILE 需提供内容或字节）");
    }
}
