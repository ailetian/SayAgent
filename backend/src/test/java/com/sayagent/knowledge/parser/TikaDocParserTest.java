package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.K3TestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 老 DOC 解析器单测：覆盖「真实 .doc 抽出文本」+「.docx 改名 .doc 被格式校验拒绝」两路。
 */
public class TikaDocParserTest {

    private final TikaDocParser parser = new TikaDocParser();

    @Test
    public void testParse_validDoc_returnsText() throws Exception {
        byte[] doc = K3TestSupport.buildDoc();
        String text = parser.parse(doc, "a.doc");
        assertTrue(text != null && !text.isBlank(), "应能从真实 .doc 抽出非空文本，实际: " + text);
        // 夹具 sample.doc 含可提取正文（WPS 模板提示语），证明走的是真实 HWPF 解析而非空串兜底
        assertTrue(text.contains("WPS"), "提取文本应含可识别正文片段，实际: " + text);
    }

    @Test
    public void testParse_docxRenamedToDoc_throwsFormatCorrupted() throws Exception {
        // 实为 docx（ZIP/PK 头），假称 .doc —— OLE2 魔数校验应拦下，防止两种格式串台
        byte[] docx = K3TestSupport.buildDocx();
        BizException ex = assertThrows(BizException.class, () -> parser.parse(docx, "fake.doc"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }

    @Test
    public void testParse_empty_throwsFormatCorrupted() {
        BizException ex = assertThrows(BizException.class, () -> parser.parse(new byte[0], "empty.doc"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }
}
