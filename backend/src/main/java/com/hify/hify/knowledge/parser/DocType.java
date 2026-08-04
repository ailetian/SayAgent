package com.hify.hify.knowledge.parser;

/**
 * 支持的文档类型（K3 解析分层路由用）。
 *
 * <p>大白话：上传的文件五花八门，先归个类——TXT 直读、MD 按标题、PDF/DOCX 走 Tika。
 * 具体用哪个解析器、切法怎么选，都看这个枚举。
 */
public enum DocType {
    /** 纯文本（.txt）。 */
    TXT,
    /** Markdown（.md/.markdown），原生解析保留标题层级。 */
    MD,
    /** PDF（.pdf），Tika(PDFBox) 解析。 */
    PDF,
    /** Word 文档（.docx），Tika(POI) 解析。 */
    DOCX
}
