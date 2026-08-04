package com.hify.hify.knowledge.chunk;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.parser.DocType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档切片器（K3 R1 切片自适应）。
 *
 * <p>大白话：解析出的纯文本要切成「一张卡片讲清一件事」的小块。<b>切多大、重叠多少全听
 * {@link RagConfig} 的</b>（库级可覆盖全局），代码里<b>绝不写死 500/50</b>（§7.2 规则7）。
 * 切法按文档类型选：
 * <ul>
 *   <li>MD → {@link ChunkStrategy#MARKDOWN_HEADER}：按标题层级切，并把父级标题带进每块当上下文。</li>
 *   <li>TXT/PDF/DOCX → {@link ChunkStrategy#RECURSIVE}：按段落累积，超长段落再按句子/字切，块间留 overlap 防切断。</li>
 * </ul>
 * 每块写连续 {@code seq}（1,2,3...），落 pg {@code document_chunk.seq}，供 Small-to-Big(R5) 与溯源。
 */
public class DocumentChunker {

    /**
     * 配置异常时的<b>防御性</b>兜底值（正常路径 RagConfig 已保证 chunkSize>0，这里仅防配置被破坏，
     * 不参与任何正常切块，故不构成「魔法数字」）。
     */
    private static final int FALLBACK_CHUNK_SIZE = 800;

    /** 标题行识别：行首 1~6 个 # 后跟空格再跟内容。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+.*$");

    /** 规章条款识别：行首「第X条」或「Article N」——按条切的天然边界（R1 规章按条）。 */
    private static final Pattern ARTICLE = Pattern.compile(
            "(?m)^\\s*(第[零一二三四五六七八九十百千0-9]+条|Article\\s+[0-9]+).*$");

    /** 按文档类型自动选策略并切片。 */
    public List<Chunk> chunk(String text, DocType docType, RagConfig config) {
        ChunkStrategy strategy = (docType == DocType.MD) ? ChunkStrategy.MARKDOWN_HEADER : ChunkStrategy.RECURSIVE;
        return chunk(text, strategy, config);
    }

    /** 按显式策略切片（AUTO 在此解析为 RECURSIVE）。 */
    public List<Chunk> chunk(String text, ChunkStrategy strategy, RagConfig config) {
        if (text == null) {
            text = "";
        }
        int size = config.chunkSize();
        int overlap = config.chunkOverlap();
        if (size <= 0) {
            size = FALLBACK_CHUNK_SIZE;
        }
        if (overlap < 0) {
            overlap = 0;
        }
        if (overlap >= size) {
            overlap = size / 2;
        }

        ChunkStrategy resolved = (strategy == ChunkStrategy.AUTO) ? ChunkStrategy.RECURSIVE : strategy;
        List<String> pieces;
        if (resolved == ChunkStrategy.MARKDOWN_HEADER) {
            pieces = splitByMarkdownHeading(text, size, overlap);
        } else if (looksLikeRegulation(text)) {
            // 规章按条切：识别「第X条/Article N」作为边界，与 MD 按标题同构
            pieces = splitByArticle(text, size, overlap);
        } else {
            pieces = splitRecursive(text, size, overlap);
        }
        return assignSeq(pieces);
    }

    /** 给非空片段顺次编 seq（1 起）。 */
    private List<Chunk> assignSeq(List<String> pieces) {
        List<Chunk> chunks = new ArrayList<>(pieces.size());
        int seq = 1;
        for (String piece : pieces) {
            String trimmed = piece.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(seq++, trimmed));
        }
        return chunks;
    }

    // ===================== 递归字符切分 =====================

    private List<String> splitRecursive(String text, int size, int overlap) {
        List<String> paragraphs = splitParagraphs(text);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (para.length() > size) {
                // 当前累积先落盘（末尾 overlap 作为下一块前缀）
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder(tail(current.toString(), overlap));
                }
                result.addAll(splitLongSegment(para, size, overlap));
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + para.length() > size) {
                result.add(current.toString());
                current = new StringBuilder(tail(current.toString(), overlap));
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(para);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /** 单段超长时按句子再切，段间带 overlap。 */
    private List<String> splitLongSegment(String para, int size, int overlap) {
        List<String> sentences = splitSentences(para);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() > 0 && cur.length() + 1 + s.length() > size) {
                out.add(cur.toString());
                cur = new StringBuilder(tail(cur.toString(), overlap));
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(s);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ===================== Markdown 标题切分 =====================

    private List<String> splitByMarkdownHeading(String text, int size, int overlap) {
        List<String> sections = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (HEADING.matcher(line).find()) {
                if (section.length() > 0) {
                    sections.add(section.toString().strip());
                    section.setLength(0);
                }
                section.append(line).append('\n');
            } else {
                section.append(line).append('\n');
            }
        }
        if (section.length() > 0) {
            sections.add(section.toString().strip());
        }

        List<String> result = new ArrayList<>();
        for (String sec : sections) {
            if (sec.length() <= size) {
                result.add(sec);
                continue;
            }
            // 超长小节：保留其标题作上下文前缀，正文走递归切
            String headingLine = extractFirstHeading(sec);
            String body = sec.substring(headingLine.length()).strip();
            List<String> sub = splitRecursive(body, Math.max(size - headingLine.length(), 1), overlap);
            for (String s : sub) {
                result.add(headingLine.strip() + "\n" + s);
            }
        }
        return result;
    }

    private String extractFirstHeading(String sec) {
        for (String line : sec.split("\n")) {
            if (HEADING.matcher(line).find()) {
                return line + "\n";
            }
        }
        return "";
    }

    // ===================== 规章按条切分 =====================

    private List<String> splitByArticle(String text, int size, int overlap) {
        List<String> sections = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (ARTICLE.matcher(line).find()) {
                if (section.length() > 0) {
                    sections.add(section.toString().strip());
                    section.setLength(0);
                }
                section.append(line).append('\n');
            } else {
                section.append(line).append('\n');
            }
        }
        if (section.length() > 0) {
            sections.add(section.toString().strip());
        }

        List<String> result = new ArrayList<>();
        for (String sec : sections) {
            if (sec.length() <= size) {
                result.add(sec);
                continue;
            }
            // 超长条款：保留条款号作上下文前缀，正文走递归切
            String articleLine = extractFirstArticle(sec);
            String body = sec.substring(articleLine.length()).strip();
            List<String> sub = splitRecursive(body, Math.max(size - articleLine.length(), 1), overlap);
            for (String s : sub) {
                result.add(articleLine.strip() + "\n" + s);
            }
        }
        return result;
    }

    private String extractFirstArticle(String sec) {
        for (String line : sec.split("\n")) {
            if (ARTICLE.matcher(line).find()) {
                return line + "\n";
            }
        }
        return "";
    }

    /** 文本是否像规章（含「第X条/Article N」条款标记）。 */
    private boolean looksLikeRegulation(String text) {
        for (String line : text.split("\n", -1)) {
            if (ARTICLE.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    // ===================== 文本切分工具 =====================

    private List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n")) {
            String t = p.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private List<String> splitSentences(String para) {
        // 以中英文句末标点切，保留标点（中文按句、英文按 .!?）
        List<String> out = new ArrayList<>();
        for (String s : para.split("(?<=[。.!?！？])")) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 取字符串末尾 overlap 个字符，作为下一段的重叠前缀。 */
    private String tail(String str, int overlap) {
        if (overlap <= 0 || str.length() <= overlap) {
            return "";
        }
        return str.substring(str.length() - overlap);
    }
}
