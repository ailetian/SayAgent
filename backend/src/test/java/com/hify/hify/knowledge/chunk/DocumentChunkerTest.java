package com.hify.hify.knowledge.chunk;

import com.hify.hify.knowledge.K3TestSupport;
import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.parser.DocType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    public void testMarkdown_byHeading_chunkCountMatchesHeadings() {
        String md = "# 第一章\n内容一\n\n## 第一节\n内容二\n\n# 第二章\n内容三\n";
        RagConfig cfg = K3TestSupport.ragConfig(800, 120);
        List<Chunk> chunks = chunker.chunk(md, DocType.MD, cfg);
        assertEquals(3, chunks.size(), "三个标题应切成三块");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).seq(), "seq 应连续");
        }
    }

    @Test
    public void testRecursive_respectsSizeAndOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是第").append(i).append("个句子。");
        }
        String text = sb.toString();
        RagConfig cfg = K3TestSupport.ragConfig(200, 30);
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, cfg);
        assertTrue(chunks.size() >= 2, "长文本应被切成多块");

        int limit = cfg.chunkSize() + cfg.chunkOverlap() + 5;
        for (Chunk c : chunks) {
            assertTrue(c.content().length() <= limit,
                    "单块长度不应超过 size+overlap，实际=" + c.content().length());
        }
        // 相邻块应存在重叠片段（防切断）
        boolean hasOverlap = false;
        for (int i = 1; i < chunks.size(); i++) {
            String prevTail = chunks.get(i - 1).content();
            String curHead = chunks.get(i).content();
            if (prevTail.length() >= 10 && curHead.contains(prevTail.substring(prevTail.length() - 10))) {
                hasOverlap = true;
                break;
            }
        }
        assertTrue(hasOverlap, "相邻块之间应存在重叠片段");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).seq(), "seq 应连续");
        }
    }

    @Test
    public void testRegulation_byArticle_splitsPerClause() {
        String reg = "第一条 本规定适用于全体。\n\n第二条 违规者将受罚。\n\n第三条 本办法自发布日起施行。\n";
        RagConfig cfg = K3TestSupport.ragConfig(800, 120);
        List<Chunk> chunks = chunker.chunk(reg, ChunkStrategy.RECURSIVE, cfg);
        assertEquals(3, chunks.size(), "三条规章应切成三块");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).seq());
        }
        assertTrue(chunks.get(0).content().contains("第一条"));
        assertTrue(chunks.get(1).content().contains("第二条"));
        assertTrue(chunks.get(2).content().contains("第三条"));
    }

    @Test
    public void testChunkSizeFromConfig_notHardcoded() {
        String text = repeat("短句。", 1000);
        int big = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(1000, 100)).size();
        int small = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(200, 30)).size();
        assertTrue(small > big, "小切块应产生更多块: small=" + small + " big=" + big);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    // ===================== T1（K0808）：小数点/版本号误切修复 =====================

    @Test
    public void testDecimal_3_5_notSplit() {
        // 3.5 是小数，不能在小数点切断成 "3." + "5秒"
        String text = "系统延迟3.5秒后自动重试";
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(800, 120));
        assertEquals(1, chunks.size(), "3.5 不应在小数点切断");
        assertEquals("系统延迟3.5秒后自动重试", chunks.get(0).content());
    }

    @Test
    public void testDecimal_version_notSplit() {
        // v1.0.3 是版本号，不能在任意小数点切断
        String text = "请升级到 v1.0.3 版本后重试";
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(800, 120));
        assertEquals(1, chunks.size(), "v1.0.3 不应在小数点切断");
        assertEquals("请升级到 v1.0.3 版本后重试", chunks.get(0).content());
    }

    @Test
    public void testDecimal_section_notSplit() {
        // 第4.2节：小数点夹在中文里，仍属同一表述
        String text = "详见第4.2节说明中的注意事项";
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(800, 120));
        assertEquals(1, chunks.size(), "第4.2节 不应在小数点切断");
        assertEquals("详见第4.2节说明中的注意事项", chunks.get(0).content());
    }

    @Test
    public void testCjkPeriod_splitIntoTwo() {
        // 正常中文句号仍应切成 2 段（小切块强制句级分块）
        String text = "年假最多可请15天。事假需提前一天申请。";
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(10, 0));
        assertEquals(2, chunks.size(), "应按 。 切成 2 段");
        assertEquals("年假最多可请15天。", chunks.get(0).content());
        assertEquals("事假需提前一天申请。", chunks.get(1).content());
    }

    @Test
    public void testEnglishDecimal_404_notCut() {
        // 含数字 404 的中文句仍按 。 切，且数字不被切断
        String text = "HTTP 404 表示未找到。HTTPS 是加密协议。";
        List<Chunk> chunks = chunker.chunk(text, ChunkStrategy.RECURSIVE, K3TestSupport.ragConfig(10, 0));
        assertEquals(2, chunks.size(), "应按 。 切成 2 段");
        assertEquals("HTTP 404 表示未找到。", chunks.get(0).content());
        assertEquals("HTTPS 是加密协议。", chunks.get(1).content());
    }
}
