package com.sayagent.rbac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 资源授权审计仓储（M10/T6）。
 *
 * <p>大白话：对 {@code resource_access_audit} 追加写 + 按资源维度查询（事后追溯某 Agent 的授权历史）。
 * 不继承 {@code BaseRepository}（实体不继承 {@code BaseEntity}，与 {@link ResourceAccess} 一致）。
 */
@Repository
public interface ResourceAccessAuditRepository extends JpaRepository<ResourceAccessAudit, Long> {

    /**
     * 按资源（类型 + id）倒序列出审计记录（最新在前），用于授权历史追溯。
     */
    List<ResourceAccessAudit> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, Long resourceId);
}
