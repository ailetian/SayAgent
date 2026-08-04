package com.hify.hify.knowledge.parser;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * TXT 直读解析器（§4 解析分层）。
 *
 * <p>大白话：TXT 没有结构，直接按 UTF-8 读出来就行，不需要 Tika。
 * 但有个坑：有人会把图片改个 .txt 后缀来蒙混，所以顺手查一下「替换符」占比，
 * 占比过高说明其实是二进制文件伪装，直接报格式损坏（防改后缀绕过，§7.11 规则36）。
 */
public class TextParser implements DocumentParser {

    /** 替换符（\uFFFD）占比超过该阈值即判定为二进制伪装（§7.11 规则36）。 */
    private static final double REPLACEMENT_RATIO_THRESHOLD = 0.01;

    @Override
    public DocType supportedType() {
        return DocType.TXT;
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "TXT 内容为空");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (containsTooManyReplacementChars(text)) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "TXT 含大量非法字节，疑似非文本文件");
        }
        return text;
    }

    /** 估算文本里的替换符占比，过高即视为二进制伪装。 */
    private boolean containsTooManyReplacementChars(String text) {
        if (text.isEmpty()) {
            return false;
        }
        long count = text.chars().filter(c -> c == '\uFFFD').count();
        return (double) count / text.length() > REPLACEMENT_RATIO_THRESHOLD;
    }
}
