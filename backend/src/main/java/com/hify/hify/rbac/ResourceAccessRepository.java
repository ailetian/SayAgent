package com.hify.hify.rbac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 统一资源授权仓储（M9/T5）。
 *
 * <p>大白话：对 {@code resource_access} 表做增删查。
 * 注意：本仓储<b>不继承 {@code BaseRepository}</b>——{@code BaseRepository} 约束 {@code <T extends BaseEntity}，
 * 而 {@link ResourceAccess} 因 P2-8 <b>不继承 BaseEntity</b>，故直接继承 {@code JpaRepository}。
 *
 * <p>方法命名严格对齐 DDL 列名（principal_type / principal_id / resource_type / resource_id / can_read）。
 */
@Repository
public interface ResourceAccessRepository extends JpaRepository<ResourceAccess, Long> {

    /**
     * 查某用户（principal_type=USER）对某类资源<b>可读（can_read=1）</b>的全部授权行。
     * 用于「用户可见资源 id 集合」（visibleResourceIds）。
     */
    List<ResourceAccess> findByPrincipalTypeAndPrincipalIdAndResourceTypeAndCanReadTrue(
            String principalType, String principalId, String resourceType);

    /**
     * 查某角色集（principal_type=ROLE，principal_id IN 角色集）对某类资源<b>可读</b>的全部授权行。
     * 用于「角色基线授权」可见性（§2.1）：当前用户任一角色命中即视为可见/可用。
     */
    List<ResourceAccess> findByPrincipalTypeAndPrincipalIdInAndResourceTypeAndCanReadTrue(
            String principalType, Collection<String> principalIds, String resourceType);

    /**
     * 查某主体对某资源的精确授权行（用于 grantCreator 幂等：已存在则不重复插）。
     */
    Optional<ResourceAccess> findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
            String principalType, String principalId, String resourceType, Long resourceId);

    /**
     * 撤销某主体对某资源的授权（T7 用）。
     */
    void deleteByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
            String principalType, String principalId, String resourceType, Long resourceId);

    /**
     * 查某资源当前的全部授权行（T7 授权 Tab「列出当前授权」用）。
     */
    List<ResourceAccess> findByResourceTypeAndResourceId(String resourceType, Long resourceId);
}
