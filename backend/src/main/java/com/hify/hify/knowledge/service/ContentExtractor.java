package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.knowledge.entity.Document;

/**
 * 索引流水线「取原文」抽象（K6）。
 *
 * <p>大白话：上传的文件千奇百怪——有的是直接粘的文本、有的是 URL、有的是 PDF 字节。
 * 这一层专门负责"给流水线一段能切片的纯文本"，把"文件从哪来、怎么变成文本"和
 * "索引状态机怎么流转"彻底解耦（§3.2 跨职责拆分）。
 *
 * <p>生产实现（{@code DocumentContentExtractor}）规则：
 * <ul>
 *   <li>文档已带 {@code rawContent}（TEXT/URL/内容型上传）→ 直接返回，跳过解析。</li>
 *   <li>FILE 且提供字节 → 按扩展名路由到对应解析器（Tika 等），翻不动抛细分错误码（加密/扫描件/损坏）。</li>
 *   <li>取不到任何文本 → 抛 {@link com.hify.hify.common.exception.ErrorCode#FORMAT_CORRUPTED}。</li>
 * </ul>
 *
 * <p>单测里注入本接口的桩，既能喂固定文本，也能故意抛 {@code ENCRYPTED_PDF} 之类验证 PARSE 阶段失败路径，
 * 不用真的去读文件（坑位2：单测不连真库）。
 */
@FunctionalInterface
public interface ContentExtractor {

    /**
     * 取出该文档待切片解析的纯文本。
     *
     * @param doc      文档实体（可读取 rawContent / sourceRef / sourceType）
     * @param filename 原始文件名（FILE 型用于路由解析器，可空）
     * @return 纯文本（非空；无文本层按"扫描件"语义抛对应错误码）
     * @throws BizException 解析失败时抛出对应细分错误码（加密/扫描件/损坏），禁止吞异常
     */
    String extract(Document doc, String filename);
}
