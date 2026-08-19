package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import org.apache.tika.Tika;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * PDF 解析器（§4 解析分层）：底层走 Tika（Tika 内部用 PDFBox）。
 *
 * <p>大白话：PDF 最坑——有的加密打不开、有的扫描件没文字层、有的干脆是张图片改了后缀。
 * 这三种必须报<b>不同的错误码</b>（§3.5/§7.3），不能统一甩一句「解析失败」：
 * <ul>
 *   <li>文件头不是 {@code %PDF} → {@link ErrorCode#FORMAT_CORRUPTED}（防改后缀绕过，§7.11 规则36）</li>
 *   <li>加密 → {@link ErrorCode#ENCRYPTED_PDF}</li>
 *   <li>能打开但 Extract 不出文字（扫描件）→ {@link ErrorCode#SCANNED_PDF_NO_TEXT}</li>
 *   <li>其他解析异常 → {@link ErrorCode#FORMAT_CORRUPTED}</li>
 * </ul>
 */
public class TikaPdfParser implements DocumentParser {

    /** PDF 文件头魔数（"%PDF"）。 */
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

    private final Tika tika = new Tika();

    @Override
    public DocType supportedType() {
        return DocType.PDF;
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "PDF 内容为空");
        }
        // 魔数校验：扩展名可能是假的（图片改 .pdf），必须以文件头为准
        if (!startsWith(content, PDF_MAGIC)) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "文件头不是 %PDF，疑似非 PDF 文件");
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            Metadata metadata = new Metadata();
            // 强制按 PDF 解析，避免 Tika 自动探测把坏文件误判成别的类型而吞掉错误
            metadata.set(Metadata.CONTENT_TYPE, "application/pdf");
            String text = tika.parseToString(in, metadata);
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.SCANNED_PDF_NO_TEXT, "PDF 无可提取文本层，疑似扫描件");
            }
            return text;
        } catch (EncryptedDocumentException e) {
            throw new BizException(ErrorCode.ENCRYPTED_PDF, "PDF 已加密");
        } catch (BizException e) {
            // 已经是我们自己定义的细分错误，直接透传，不要二次包裹
            throw e;
        } catch (IOException | TikaException e) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "PDF 解析失败：" + e.getMessage());
        }
    }

    /** 比较字节前缀（不依赖 String 构造，避免编码干扰）。 */
    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
