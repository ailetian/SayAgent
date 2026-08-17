package com.hify.hify.common.tool;

/**
 * 工具风险分级（M10/T3，§3.2 统一工具契约增强）。
 *
 * <p>大白话：给每个工具贴一张"危险度"标签，供 T5 执行闸（高危需二次确认/拦截）与 T6 前端知情预览使用。
 * 级别越高越危险——执行层据此决定「直接放行 / 二次确认 / 直接拦截」。
 */
public enum RiskLevel {
    /** L0 只读安全：无副作用。如查库存、查报价、current-time、知识检索。 */
    L0_READONLY_SAFE("只读安全，无副作用"),
    /** L1 写但可逆/内部：如生成草稿、建内部临时记录。协议未声明时的默认级别（宁严，§7.3 前置防御）。 */
    L1_WRITE_REVERSIBLE("写操作但可逆/内部"),
    /** L2 对外不可逆副作用：如取消订单、删除记录、对外发消息。 */
    L2_IRREVERSIBLE("对外不可逆副作用"),
    /** L3 高危外发：资金/法律/批量操作。如转款、发合同、批量删除。 */
    L3_HIGH_RISK("高危外发，资金/法律/批量");

    /** 级别中文含义，供日志与前端展示（§3.7 自解释，不靠注释补上下文）。 */
    public final String desc;

    RiskLevel(String desc) {
        this.desc = desc;
    }
}
