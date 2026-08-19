package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.K3TestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TikaPdfParserTest {

    private final TikaPdfParser parser = new TikaPdfParser();

    @Test
    public void testParse_validPdf_returnsText() throws Exception {
        byte[] pdf = K3TestSupport.buildValidPdf();
        String text = parser.parse(pdf, "a.pdf");
        assertTrue(text.contains("Hello World PDF"), "提取文本应含 Hello World PDF，实际: " + text);
    }

    @Test
    public void testParse_encryptedPdf_throwsEncryptedPdf() throws Exception {
        byte[] pdf = K3TestSupport.buildEncryptedPdf();
        BizException ex = assertThrows(BizException.class, () -> parser.parse(pdf, "a.pdf"));
        assertEquals(ErrorCode.ENCRYPTED_PDF, ex.getErrorCode());
    }

    @Test
    public void testParse_scannedPdf_throwsScannedNoText() throws Exception {
        byte[] pdf = K3TestSupport.buildScannedPdf();
        BizException ex = assertThrows(BizException.class, () -> parser.parse(pdf, "a.pdf"));
        assertEquals(ErrorCode.SCANNED_PDF_NO_TEXT, ex.getErrorCode());
    }

    @Test
    public void testParse_nonPdfBytes_throwsFormatCorrupted() {
        byte[] fake = K3TestSupport.binaryBytes();
        BizException ex = assertThrows(BizException.class, () -> parser.parse(fake, "a.pdf"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }
}
