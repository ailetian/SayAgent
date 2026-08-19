package com.sayagent.rbac.dto;

/**
 * 授权行视图（M9/T7，GET /api/resource-access 返回）。
 *
 * <p>大白话：前端「授权」Tab 列出某资源当前被授权了哪些主体（角色/用户）和各自的四权，
 * 用这个只读视图装，避免直接把 {@code ResourceAccess} 实体（含 created_at 等内部字段）序列化出去。
 */
public class ResourceAccessView {

    /** 授权主体类型：USER / ROLE。 */
    private final String principalType;

    /** 授权主体 id：角色名或登录名。 */
    private final String principalId;

    private final boolean canRead;

    private final boolean canWrite;

    private final boolean canUse;

    private final boolean canEdit;

    public ResourceAccessView(String principalType, String principalId,
                              boolean canRead, boolean canWrite, boolean canUse, boolean canEdit) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.canUse = canUse;
        this.canEdit = canEdit;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public boolean isCanRead() {
        return canRead;
    }

    public boolean isCanWrite() {
        return canWrite;
    }

    public boolean isCanUse() {
        return canUse;
    }

    public boolean isCanEdit() {
        return canEdit;
    }
}
