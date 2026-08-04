package com.hify.hify.knowledge.parser;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * Markdown 原生解析器（§4 解析分层）。
 *
 * <p>大白话：MD 必须走自己的解析，<b>不能丢给 Tika</b>——Tika 会把标题层级拍平成一坨纯文本，
 * 后面就没法「按标题切块」了。这里只做 UTF-8 解码 + 换行归一，<b>原样保留 {@code #} 标题</b>，
 * 把结构留给 {@code DocumentChunker} 的 MARKDOWN_HEADER 策略去切。
 */
public class MarkdownParser implements DocumentParser {

    /** 同 TextParser 的二进制伪装判定阈值（§7.11 规则36）。 */
    private static final double REPLACEMENT_RATIO_THRESHOLD = 0.01;

    @Override
    public DocType supportedType() {
        return DocType.MD;
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "Markdown 内容为空");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (containsTooManyReplacementChars(text)) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "Markdown 含大量非法字节，疑似非文本文件");
        }
        // 归一换行，便于切片按标题切分；标题层级（# 标记）原样保留
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private boolean containsTooManyReplacementChars(String text) {
        if (text.isEmpty()) {
            return false;
        }
        long count = text.chars().filter(c -> c == '\uFFFD').count();
        return (double) count / text.length() > REPLACEMENT_RATIO_THRESHOLD;
    }
}
