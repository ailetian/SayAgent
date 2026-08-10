package com.hify.hify.common.tool;

import com.hify.hify.common.tool.builtin.CurrentTimeTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 内置工具注册表（M8/T1，§3.7 单文件规模：只做"名→Tool"映射，不含执行细节）。
 *
 * <p>大白话：平台自带几个"永远可用、无需联网"的工具，先在这里登记。
 * {@code current-time} 是最简单的台灯，用来证明"模型能自己决定插这个插头、用它、再回答"。
 */
@Component
public class BuiltinToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public BuiltinToolRegistry() {
        register(new CurrentTimeTool());
    }

    /** 登记一个工具（以它的定义名为 key）。 */
    public void register(Tool tool) {
        tools.put(tool.getDefinition().name(), tool);
    }

    /** 按名字取工具；没有返回 null。 */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 列出所有已注册工具的定义（供前端展示 / 拼进模型 function 列表）。 */
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool t : tools.values()) {
            defs.add(t.getDefinition());
        }
        return defs;
    }

    /** 已注册工具名集合。 */
    public Set<String> names() {
        return tools.keySet();
    }

    /** 所有已注册工具实例（供编排循环直接执行，如 current-time）。 */
    public List<Tool> allTools() {
        return new ArrayList<>(tools.values());
    }
}
