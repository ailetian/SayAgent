package com.sayagent.knowledge.chunk;

/**
 * 切片策略（K3 R1 切片自适应）。
 *
 * <p>大白话：不同文档「一块讲清一件事」的切法不一样——
 * <ul>
 *   <li>{@code AUTO}：交给 {@link DocumentChunker} 按文档类型自动选（MD→标题切，其余→递归切）。</li>
 *   <li>{@code RECURSIVE}：递归字符切，长文/FAQ/规章的兜底切法，size+overlap 来自 RagConfig。</li>
 *   <li>{@code MARKDOWN_HEADER}：按 Markdown 标题层级切，保留父级标题上下文。</li>
 * </ul>
 */
public enum ChunkStrategy {
    /** 自动：按文档类型选（MD→标题切，其余→递归切）。 */
    AUTO,
    /** 递归字符切分（长文/FAQ/规章兜底）。 */
    RECURSIVE,
    /** 按 Markdown 标题层级切（保留标题上下文）。 */
    MARKDOWN_HEADER
}
