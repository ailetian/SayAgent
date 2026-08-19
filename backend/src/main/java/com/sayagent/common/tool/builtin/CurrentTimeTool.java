package com.sayagent.common.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sayagent.common.tool.RiskLevel;
import com.sayagent.common.tool.Tool;
import com.sayagent.common.tool.ToolDefinition;
import com.sayagent.common.tool.ToolResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 内置工具：返回当前时间（M8/T1，§7.11 配置外置——时区先写死 Asia/Shanghai，留 TODO 接配置）。
 *
 * <p>大白话：一个永远准时的台灯。模型若需要"现在几点"，就调它，拿到 ISO8601 时间串（带 +08:00 时区）。
 */
public class CurrentTimeTool implements Tool {

    /** 时区：先写死 Asia/Shanghai，待配置中心就绪后外置（§7.11）。 */
    // TODO(M8/T1): 接入配置中心 ZoneId，避免硬编码
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ToolDefinition DEF = new ToolDefinition(
            "current-time",
            "返回当前的日期与时间，格式为 ISO8601（含时区偏移，如 2026-08-06T16:30:00+08:00）。"
                    + "当用户问及「现在几点」「今天日期」「当前时间」时使用。",
            emptySchema(),
            RiskLevel.L0_READONLY_SAFE);

    /** 无参入参 schema：{"type":"object","properties":{}}（§3.5 强类型，复用 OpenAI function-calling 结构）。 */
    private static com.fasterxml.jackson.databind.JsonNode emptySchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        node.set("properties", mapper.createObjectNode());
        return node;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            // 入参忽略（current-time 无需参数）
            OffsetDateTime now = OffsetDateTime.now(ZONE);
            return ToolResult.ok(now.toString());
        } catch (Exception e) {
            // §7.3：永不对外抛异常，内部兜底失败
            return ToolResult.fail("current-time 执行异常: " + e.getMessage());
        }
    }
}
