package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.K3TestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    public void testParse_preservesHeadings() {
        String md = "# 标题一\n正文一\n## 标题二\n正文二\n";
        byte[] bytes = md.getBytes(StandardCharsets.UTF_8);
        String text = parser.parse(bytes, "a.md");
        assertTrue(text.contains("# 标题一"), "应保留一级标题");
        assertTrue(text.contains("## 标题二"), "应保留二级标题");
    }

    @Test
    public void testParse_binary_throwsFormatCorrupted() {
        BizException ex = assertThrows(BizException.class,
                () -> parser.parse(K3TestSupport.binaryBytes(), "a.md"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }
}
