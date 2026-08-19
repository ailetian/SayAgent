package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.K3TestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TextParserTest {

    private final TextParser parser = new TextParser();

    @Test
    public void testParse_plainText_returnsContent() {
        byte[] bytes = "hello world 你好".getBytes(StandardCharsets.UTF_8);
        String text = parser.parse(bytes, "a.txt");
        assertEquals("hello world 你好", text);
    }

    @Test
    public void testParse_binaryContent_throwsFormatCorrupted() {
        BizException ex = assertThrows(BizException.class,
                () -> parser.parse(K3TestSupport.binaryBytes(), "a.txt"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }

    @Test
    public void testParse_empty_throwsFormatCorrupted() {
        BizException ex = assertThrows(BizException.class, () -> parser.parse(new byte[0], "a.txt"));
        assertEquals(ErrorCode.FORMAT_CORRUPTED, ex.getErrorCode());
    }
}
