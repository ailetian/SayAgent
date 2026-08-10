package com.hify.hify.common.tool;

/**
 * 工具执行结果（M8/T1，§3.5 强类型 DTO）。
 *
 * <p>大白话：工具干完活的交代——成没成（success）、产出了啥（content）、失败原因（errorMessage）。
 */
public record ToolResult(boolean success, String content, String errorMessage) {

    /** 成功结果。 */
    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    /**
     * 失败结果（§7.3 规则：本任务纯内存逻辑不抛 BizException，失败一律内部兜底成 ToolResult）。
     */
    public static ToolResult fail(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }
}
