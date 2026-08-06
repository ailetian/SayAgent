package com.hify.hify.common.exception;

import lombok.Getter;

/**
 * 错误码枚举（§3.5 API 响应契约、§7.2 规则7 禁魔法数字）。
 *
 * <p>大白话：所有「哪里出了什么错」都集中登记在这张表里，每个错误是一个带固定编号的牌子。
 * 业务代码里只能写 {@code ErrorCode.MODEL_NOT_FOUND} 这种名字，<b>严禁</b>直接写 {@code 1001} 这种魔法数字。
 *
 * <p>编号分段约定（起步集，后续按需在尾部追加，不改动已有编号以免前端缓存错乱）：
 * <ul>
 *   <li>1xxx 业务资源类（模型/知识/Agent 等找不到或冲突）</li>
 *   <li>4xxx 客户端类（参数校验、未认证、无权限）</li>
 *   <li>5xxx 服务端类（系统异常兜底）</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    /** 模型不存在（M3 模型管理会用到）。 */
    MODEL_NOT_FOUND(1001, "模型不存在"),

    /** Agent 不存在（M4 Agent 配置会用到）。 */
    AGENT_NOT_FOUND(1002, "Agent 不存在"),

    /** 知识库不存在（M5 知识库会用到）。 */
    KNOWLEDGE_BASE_NOT_FOUND(1003, "知识库不存在"),

    /** MCP Server 不存在（M7 MCP 集成会用到）。 */
    MCP_SERVER_NOT_FOUND(1004, "MCP Server 不存在"),

    /** 入参校验失败（Bean Validation 抛出的异常会翻译成它）。 */
    PARAM_INVALID(4000, "参数校验失败"),

    /** 登录认证失败：用户名不存在或密码错误（M2 登录用，映射 HTTP 401）。 */
    AUTH_FAIL(4001, "用户名或密码错误"),

    /** 未认证或登录已过期（M2 登录后用到）。 */
    UNAUTHORIZED(4010, "未认证或登录已过期"),

    /** 已登录但无权限做该操作（例如普通用户改了管理员配置）。 */
    FORBIDDEN(4030, "无权限访问"),

    /** 不支持的文件类型（M5 知识库上传解析不支持的 MIME）。 */
    UNSUPPORTED_FILE_TYPE(4002, "不支持的文件类型"),

    /** 批量上传文件数超过上限（K8 单次 ≤10 个）。 */
    UPLOAD_TOO_MANY(4003, "批量上传文件数超过上限（单次最多 10 个）"),

    /** 单文件超过大小上限（K8 单次 ≤20MB）。 */
    FILE_TOO_LARGE(4004, "单文件超过大小上限（最多 20MB）"),

    /** 请求的资源/路径不存在（例如访问了不存在的 API，映射 HTTP 404，不再被兜底成 5000）。 */
    RESOURCE_NOT_FOUND(4040, "请求的资源不存在"),

    /** 大模型调用失败：上游 5xx / 超时 / 解析异常（M3 LLM 治理）。 */
    LLM_CALL_FAILED(5001, "大模型调用失败"),

    /** 向量化失败：Embedding 调用上游异常/超时（M5 知识库）。 */
    EMBEDDING_FAILED(5002, "向量化失败"),

    /** 知识检索失败：向量检索或 Pg 查询异常（M5 知识库）。 */
    RETRIEVAL_FAILED(5003, "知识检索失败"),

    /** 所有模型供应商均不可用：路由降级链全部失败（M3 LLM 治理）。 */
    LLM_ALL_PROVIDERS_FAILED(5004, "所有模型供应商均不可用"),

    /** MCP 工具调用失败：连接/执行超时或工具不存在（M7 MCP 集成）。 */
    MCP_CALL_FAILED(5005, "MCP 工具调用失败"),

    /** 文档解析失败：通用兜底（非加密/扫描件/损坏等具体分类外的解析异常，K3）。 */
    DOC_PARSE_FAILED(5006, "文档解析失败"),

    /** 加密 PDF：无法提取文本（K3）。 */
    ENCRYPTED_PDF(5007, "PDF 已加密，无法解析"),

    /** 扫描件 PDF：无可提取文本层（K3）。 */
    SCANNED_PDF_NO_TEXT(5008, "PDF 为扫描件，无文本层"),

    /** 文档格式损坏或文件头不匹配（改后缀绕过等，K3）。 */
    FORMAT_CORRUPTED(5009, "文档格式损坏或类型不符"),

    /** 索引流水线整体失败兜底（K6）：具体死因已记在 indexing_job.error_code 列，UI 精确报"❌ 解析失败：加密 PDF"之类。 */
    INDEXING_JOB_FAILED(5010, "文档索引失败"),

    /** 系统异常兜底：所有没被具体捕获的未知错误都归它。 */
    SYS_ERROR(5000, "系统异常");

    /** 错误编号（响应体里的 code 字段）。 */
    private final int code;

    /** 错误描述（响应体里的 message 字段，给用户看的浅白说明）。 */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
