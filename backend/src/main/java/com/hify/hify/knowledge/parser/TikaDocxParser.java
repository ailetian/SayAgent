package com.hify.hify.knowledge.parser;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * DOCX 解析器（§4 解析分层）：底层走 Tika（Tika 内部用 POI）。
 *
 * <p>大白话：DOCX 本质是个 ZIP 包，文件头魔数是 {@code PK}。同样要防「假后缀」——
 * 文件头不是 ZIP 就报格式损坏；能解出来但没文字（或解析异常）也归格式损坏。
 * DOCX 不像 PDF 有「加密/扫描件」这种需要单独区分的语义，故不引入额外错误码。
 */
public class TikaDocxParser implements DocumentParser {

    /** DOCX(ZIP) 文件头魔数（"PK\u0003\u0004"）。 */
    private static final byte[] ZIP_MAGIC = "PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1);

    private final Tika tika = new Tika();

    @Override
    public DocType supportedType() {
        return DocType.DOCX;
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "DOCX 内容为空");
        }
        if (!startsWith(content, ZIP_MAGIC)) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "文件头不是 PK，疑似非 DOCX 文件");
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            String text = tika.parseToString(in, metadata);
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.FORMAT_CORRUPTED, "DOCX 无文本内容或已损坏");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (IOException | TikaException e) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "DOCX 解析失败：" + e.getMessage());
        }
    }

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
