package com.hify.hify.knowledge;

import com.hify.hify.knowledge.config.RagConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * K3 单测共享夹具（仅测试用）。
 *
 * <p>用 tika-app 自带的 PDFBox/POI 构造<b>真实</b> PDF/DOCX 字节，避免手搓二进制导致的不稳定；
 * 同时提供 RagConfig 工厂，确保切块 size/overlap 来自配置而非硬编码。
 */
public final class K3TestSupport {

    private K3TestSupport() {
    }

    /** 构造一个 RagConfig（embedding 等用合理默认值，切块参数由入参决定）。 */
    public static RagConfig ragConfig(int chunkSize, int overlap) {
        return new RagConfig("bge-m3", 1024, chunkSize, overlap, 10, 4, 60, 0.6, 1, true, "simple");
    }

    /** 带可提取文本的合法 PDF。 */
    public static byte[] buildValidPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello World PDF extractable text content");
                cs.endText();
            }
            return toBytes(doc);
        }
    }

    /** 加密 PDF（用户密码 userPass）。 */
    public static byte[] buildEncryptedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("ownerPass", "userPass", new AccessPermission());
            doc.protect(policy);
            return toBytes(doc);
        }
    }

    /** 无可提取文本层的 PDF（空白页，模拟扫描件）。 */
    public static byte[] buildScannedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            return toBytes(doc);
        }
    }

    /** 合法 DOCX（含文本）。 */
    public static byte[] buildDocx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun run = p.createRun();
            run.setText("Hello DOCX World 这是文档正文");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        }
    }

    /** 明显非文本的二进制字节（验证「改后缀绕过」被魔数校验拦截）。 */
    public static byte[] binaryBytes() {
        byte[] b = new byte[512];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i % 256);
        }
        return b;
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        return baos.toByteArray();
    }
}
