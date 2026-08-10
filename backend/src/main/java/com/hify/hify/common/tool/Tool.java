package com.hify.hify.common.tool;

import java.util.Map;

/**
 * 统一工具契约（M8/T1，§3.2 共享内核）。
 *
 * <p>大白话：所有「工具」——无论是内置技能、MCP 服务器暴露的能力，还是以后 Skill 模块的工具——
 * 都实现这个接口。编排循环（M8/T3）只认这个接口，从而把"模型自己决定调用哪个工具、用它、再回答"
 * 这件事从伪调用 hack 升级成真正的函数调用通道。
 *
 * <p>契约纪律：仅两个方法，禁止往接口里塞业务细节（§3.7 单文件规模 / §3.5 强类型 DTO）。
 */
public interface Tool {

    /** 工具名片：名字、能干嘛、需要什么入参（JSON Schema）。 */
    ToolDefinition getDefinition();

    /** 真正干活：入参是模型回传的参数表，返回成功/内容/错误。 */
    ToolResult execute(Map<String, Object> args);
}
