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
}
