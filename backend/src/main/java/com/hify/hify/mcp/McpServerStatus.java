package com.hify.hify.mcp;

/**
 * MCP Server 启用状态常量（§7.2 规则7 禁魔法值：1/0 不得散落在业务代码里）。
 *
 * <p>大白话：通讯录里每个内部系统都有一个「开关」——1 表示开着（启用），0 表示关掉（停用）。
 * 以前这个 1 只在配置服务里写死过一次，加载链路压根没看它，导致「停用」形同虚设：
 * 管理员把某个 MCP Server 关掉后，只要没被软删除，对话时照样会去连它、把工具暴露给模型。
 * 现在把开关值集中登记在这里，配置服务与加载链路共用同一套判断，避免两处各写各的。
 *
 * <p>判断口径（fail-closed，宁可少加载不可误加载）：只有 {@code status == 1} 才算启用；
 * {@code 0}、{@code null}（脏数据）以及任何其他值一律当作停用处理。
 */
public final class McpServerStatus {

    /** 启用（与 DDL {@code status TINYINT DEFAULT 1} 对齐）。 */
    public static final int ENABLED = 1;

    /** 停用：不参与工具发现与调用，但记录仍在（区别于软删除 deleted=1）。 */
    public static final int DISABLED = 0;

    private McpServerStatus() {
        // 常量类，禁止实例化
    }

    /**
     * 是否处于启用状态。
     *
     * @param status 实体上的 status 值，可能为 {@code null}（脏数据）
     * @return 仅当 status 等于 {@link #ENABLED} 时返回 true
     */
    public static boolean isEnabled(Integer status) {
        return status != null && status == ENABLED;
    }
}
