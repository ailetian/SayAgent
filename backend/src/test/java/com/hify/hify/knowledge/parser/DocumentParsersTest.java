package com.hify.hify.knowledge.parser;

import com.hify.hify.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 解析器路由 + 白名单单测：验证 .doc 正确路由到 DOC，且不破坏既有 .docx/.txt/.md/.pdf 路由；
 * 同时断言上传白名单 {@code KnowledgeService.ALLOWED_EXT} 已含 .doc。
 */
public class DocumentParsersTest {

    @Test
    public void testDetectType_doc_returnsDOC() {
        assertEquals(DocType.DOC, DocumentParsers.detectType("report.doc"));
    }

    @Test
    public void testDetectType_docx_returnsDOCX_regression() {
        assertEquals(DocType.DOCX, DocumentParsers.detectType("report.docx"));
    }

    @Test
    public void testDetectType_txt_md_pdf_unchanged() {
        assertEquals(DocType.TXT, DocumentParsers.detectType("a.txt"));
        assertEquals(DocType.MD, DocumentParsers.detectType("a.md"));
        assertEquals(DocType.PDF, DocumentParsers.detectType("a.pdf"));
    }

    @Test
    public void testAllowedExt_containsDoc() throws Exception {
        Field f = KnowledgeService.class.getDeclaredField("ALLOWED_EXT");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) f.get(null);
        assertTrue(allowed.contains(".doc"), ".doc 应在上传白名单内，实际: " + allowed);
        assertTrue(allowed.contains(".docx"), ".docx 应保持未被误删，实际: " + allowed);
    }
}
