package com.sayagent.knowledge.parser;

import com.sayagent.common.exception.BizException;

/**
 * 文档解析器统一接口（K3 解析分层）。
 *
 * <p>大白话：每种格式一个实现，职责很单一——把「原始字节」翻成「纯文本」。
 * 翻不动（加密/扫描件/损坏/类型不符）就抛对应 {@link com.sayagent.common.exception.ErrorCode}，
 * 绝不让一个坏文件把整条上传链路带崩。
 */
public interface DocumentParser {

    /** 本解析器支持的文档类型（用于路由）。 */
    DocType supportedType();

    /**
     * 把文件原始字节解析成纯文本。
     *
     * @param content  文件原始字节（不会为 null）
     * @param filename 原始文件名（用于类型提示与魔数兜底校验，可空）
     * @return 解析后的纯文本（非空；无文本层按「扫描件」语义抛对应错误码）
     * @throws BizException 解析失败时抛出对应细分错误码（加密/扫描件/损坏），禁止吞异常
     */
    String parse(byte[] content, String filename);
}
