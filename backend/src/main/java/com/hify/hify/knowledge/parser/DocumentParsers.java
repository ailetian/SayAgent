package com.hify.hify.knowledge.parser;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import java.util.Map;

/**
 * 解析器路由（§4 解析分层 + §7.11 规则36 白名单）。
 *
 * <p>大白话：上传时只知道文件名，先按<b>扩展名白名单</b>推断出类型、挑对应解析器；
 * 但扩展名不可信（能改后缀），所以真正「这文件到底是不是 PDF」由解析器内部的<b>魔数校验</b>兜底。
 * 两层防护：扩展名挡大部分、魔数挡蓄意绕过。
 */
public final class DocumentParsers {

    private static final Map<DocType, DocumentParser> PARSERS = Map.of(
            DocType.TXT, new TextParser(),
            DocType.MD, new MarkdownParser(),
            DocType.PDF, new TikaPdfParser(),
            DocType.DOCX, new TikaDocxParser());

    private DocumentParsers() {
    }

    /** 按文档类型取解析器。 */
    public static DocumentParser get(DocType type) {
        DocumentParser parser = PARSERS.get(type);
        if (parser == null) {
            throw new IllegalStateException("未支持的文档类型: " + type);
        }
        return parser;
    }

    /**
     * 从文件名后缀推断文档类型（白名单，仅作路由提示）。
     *
     * @param filename 原始文件名
     * @return 推断出的文档类型
     * @throws BizException 扩展名不在白名单内 → {@link ErrorCode#UNSUPPORTED_FILE_TYPE}
     */
    public static DocType detectType(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE, "文件名为空");
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt")) {
            return DocType.TXT;
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocType.MD;
        }
        if (lower.endsWith(".pdf")) {
            return DocType.PDF;
        }
        if (lower.endsWith(".docx")) {
            return DocType.DOCX;
        }
        throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE, filename);
    }
}
