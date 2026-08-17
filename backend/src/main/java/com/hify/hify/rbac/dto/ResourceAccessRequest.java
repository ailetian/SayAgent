package com.hify.hify.rbac.dto;

/**
 * 授权管理请求体（M9/T7，§3.5 API 响应契约 / §7.2 禁魔法值）。
 *
 * <p>大白话：管理员 / 创建者调 {@code POST/DELETE /api/resource-access} 时，
 * 用这个盒子告诉后端「把哪个主体（角色或具体用户）对哪个资源（KB/AGENT）授权 / 撤销」，
 * 以及四权开关（读/写/用/编）。
 *
 * <p>字段语义与 {@link com.hify.hify.rbac.ResourceAccessService} 常量一一对应：
 * {@code principalType}=USER/ROLE、{@code resourceType}=KB/AGENT。
 */
public class ResourceAccessRequest {

    /** 授权主体类型：USER（个人覆盖）/ ROLE（角色基线）。非空。 */
    private String principalType;

    /** 授权主体 id：ROLE=角色名(ADMIN/OPERATOR/USER)；USER=登录名 username。非空。 */
    private String principalId;

    /** 资源类型：KB / AGENT。非空。 */
    private String resourceType;

    /** 资源 id：knowledge_base.id / agent.id。非空。 */
    private Long resourceId;

    /** 可读（默认开）。 */
    private boolean canRead = true;

    /** 可写（默认关）。 */
    private boolean canWrite = false;

    /** 可用（对话/调用，默认关）。 */
    private boolean canUse = false;

    /** 可编辑配置（默认关；持有者即视为资源管理者，可改授权，§2.1）。 */
    private boolean canEdit = false;

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public boolean isCanRead() {
        return canRead;
    }

    public void setCanRead(boolean canRead) {
        this.canRead = canRead;
    }

    public boolean isCanWrite() {
        return canWrite;
    }

    public void setCanWrite(boolean canWrite) {
        this.canWrite = canWrite;
    }

    public boolean isCanUse() {
        return canUse;
    }

    public void setCanUse(boolean canUse) {
        this.canUse = canUse;
    }

    public boolean isCanEdit() {
        return canEdit;
    }

    public void setCanEdit(boolean canEdit) {
        this.canEdit = canEdit;
    }
}
