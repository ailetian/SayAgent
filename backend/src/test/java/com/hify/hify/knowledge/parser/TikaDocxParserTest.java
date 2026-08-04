package com.hify.hify.knowledge.parser;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.K3TestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TikaDocxParserTest {

    private final TikaDocxParser parser = new TikaDocxParser();

    @Test
    public void testParse_validDocx_returnsText() throws Exception {
        byte[] docx = K3TestSupport.buildDocx();
        String text = parser.parse(docx, "a.docx");
        assertTrue(text.contains("Hello DOCX World"), "提取文本应含 Hello DOCX World，实际: " + text);
    }

    @Test
    public void testParse_nonDocxBytes_throwsFormatCorrupted() {
        byte[] fake = K3TestSupport.binaryBytes();
        BizException ex = assertThrows(BizException.class, () -> parser.parse(fake, "a.docx"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }
}
