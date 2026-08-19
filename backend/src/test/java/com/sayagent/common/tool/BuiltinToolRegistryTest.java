package com.sayagent.common.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BuiltinToolRegistry 单测（M8/T1 验收点）。
 *
 * <p>纯内存逻辑，不依赖 Spring / 数据库（§7.3：不抛 BizException）。
 */
class BuiltinToolRegistryTest {

    @Test
    void registryListsCurrentTime() {
        BuiltinToolRegistry registry = new BuiltinToolRegistry();
        List<ToolDefinition> defs = registry.listTools();
        assertTrue(defs.stream().anyMatch(d -> "current-time".equals(d.name())),
                "注册表应至少包含 current-time");
    }

    @Test
    void currentTimeExecutesAndReturnsIso8601() {
        BuiltinToolRegistry registry = new BuiltinToolRegistry();
        Tool tool = registry.get("current-time");
        assertNotNull(tool, "应能按名取到 current-time");

        ToolResult result = tool.execute(Map.of());
        assertTrue(result.success(), "current-time 应永远成功");
        assertNotNull(result.content(), "成功结果应带内容");

        // 内容须为合法 ISO8601（可被 OffsetDateTime 解析）
        OffsetDateTime parsed = OffsetDateTime.parse(result.content());
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        assertEquals(parsed.getYear(), now.getYear(), "年份应与当前一致");
        assertEquals(parsed.getMonth(), now.getMonth(), "月份应与当前一致");

        // 允许 ±1 秒误差
        long diffSec = Math.abs(Duration.between(parsed, now).getSeconds());
        assertTrue(diffSec <= 1, "与当前时间差应 ≤1 秒，实际 " + diffSec);
    }

    @Test
    void toolInterfaceHasExactlyTwoMethods() {
        java.util.Set<String> methods = java.util.Arrays.stream(Tool.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(methods.contains("getDefinition"));
        assertTrue(methods.contains("execute"));
        // RAG 重构后 Tool 接口新增了 riskLevel()/dataSensitivity() 两个 default 方法
        assertTrue(methods.contains("riskLevel"));
        assertTrue(methods.contains("dataSensitivity"));
    }
}
