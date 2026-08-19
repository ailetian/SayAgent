package com.sayagent.common.tool;

/**
 * 数据敏感度标签（M10/T4，§2.1 授权知情）。
 *
 * <p>大白话：与 {@link RiskLevel}（危险度/副作用）正交——本枚举标识"读的是不是敏感数据"。
 * 例如查薪酬、读合同、读 ERP 财务全是只读（L0），却是高敏感数据，今天跟着 Agent 授权被一并下放、零隔离。
 * 管理员注册 MCP / 工具时人工标注（MCP 协议没有标准敏感度字段），用于 T6 授权页摊开知情与 T5 执行闸组合判定。
 *
 * <p>取值顺序即敏感递增序：{@code PUBLIC < INTERNAL < CONFIDENTIAL < FINANCE_HR}，
 * 故可直接用 {@code ordinal()} 比较"最高敏感度"（§3.7 自解释）。
 */
public enum DataSensitivity {
    /** 公开，无敏感信息（如公开公告、天气）。 */
    PUBLIC("公开，无敏感信息"),
    /** 内部，普通内部数据（内置工具默认，如 current-time）。 */
    INTERNAL("内部，普通内部数据（默认）"),
    /** 机密，如合同 / 客户资料 / 研发文档。 */
    CONFIDENTIAL("机密，如合同/客户资料/研发文档"),
    /** 财务·人事，如薪酬 / 财报 / 人事档案（最高敏感）。 */
    FINANCE_HR("财务·人事，如薪酬/财报/人事档案");

    /** 中文含义（§3.7 自解释，便于日志与前端展示）。 */
    public final String desc;

    DataSensitivity(String desc) {
        this.desc = desc;
    }
}
