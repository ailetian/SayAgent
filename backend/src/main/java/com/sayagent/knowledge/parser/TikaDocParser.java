package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * DOC（老版 Word，.doc）解析器（§4 解析分层）：底层走 Tika（Tika 内部用 POI HWPF）。
 *
 * <p>大白话：.doc 是 OLE2 复合文档（Office 97-2003），文件头魔数是 {@code D0 CF 11 E0}。
 * 它和 .docx（ZIP/PK 头）完全是两码事，所以必须单独校验文件头——头不是 OLE2 就报格式损坏，
 * 能解出来但没文字（或解析异常）也归格式损坏。这样能挡住"把 .docx 改名成 .doc 上传"之类的串格式行为，
 * 与 {@link TikaDocxParser} 的魔数双校验形成互补，两种格式互不串台。
 */
public class TikaDocParser implements DocumentParser {

    /** OLE2 复合文档文件头魔数（0xD0 0xCF 0x11 0xE0）。 */
    private static final byte[] OLE2_MAGIC = { (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0 };

    private final Tika tika = new Tika();

    @Override
    public DocType supportedType() {
        return DocType.DOC;
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "DOC 内容为空");
        }
        if (!startsWith(content, OLE2_MAGIC)) {
            throw new BizException(ErrorCode.FORMAT_CORRUPTED, "文件头不是 OLE2，疑似非 DOC 文件（如把 .docx 改名上传）");
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, "application/msword");
            String text = tika.parseToString(in, metadata);
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.FORMAT_CORRUPTED, "DOC 无文本内容或已损坏");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (IOException | TikaException e) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "DOC 解析失败：" + e.getMessage());
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
