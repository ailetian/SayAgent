package com.sayagent.common.tool;

/**
 * 模型回传的工具调用意图（M8/T1，§3.5 强类型 DTO）。
 *
 * <p>大白话：模型说"我要调 {@code functionName} 这个工具，参数 JSON 在 {@code argumentsJson}"，
 * 这就是它递给我们的一张"调用小票"。编排循环（M8/T3）据此 dispatch 到对应的 {@link Tool} 执行。
 */
public record ToolCall(String id, String type, String functionName, String argumentsJson) {
}
