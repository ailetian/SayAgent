package com.sayagent.rbac;

import com.sayagent.common.Result;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.rbac.dto.ResourceAccessRequest;
import com.sayagent.rbac.dto.ResourceAccessView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 资源授权管理接口「前台柜员」（M9/T7，§3.2 分层纪律：极薄——只校验参数、调服务、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/resource-access} 入口。管理员 / 资源创建者（管理者）用它给某角色或某用户
 * 授权 / 撤销，或查看该资源当前的授权清单。普通成员（非管理者）调 POST/DELETE 会被 {@code requireManager}
 * 拦成 {@link ErrorCode#FORBIDDEN}(4030)。
 *
 * <p>守卫（§2.1 授权管理）：{@code requireManager} = ADMIN <b>或</b> 持有该资源 can_edit 授权行的主体
 * （创建者由 T5 自授权保证有此行）。逻辑收口在 {@link ResourceAccessService}，本控制器零业务判断。
 */
@RestController
@RequestMapping("/api/resource-access")
@RequiredArgsConstructor
public class ResourceAccessController {

    /** 允许的授权主体类型（§7.2 禁魔法值，与 {@link ResourceAccessService} 常量对齐）。 */
    private static final Set<String> PRINCIPAL_TYPES = Set.of(
            ResourceAccessService.PRINCIPAL_USER, ResourceAccessService.PRINCIPAL_ROLE);

    /** 允许的资源类型（§7.2 禁魔法值）。 */
    private static final Set<String> RESOURCE_TYPES = Set.of(
            ResourceAccessService.RESOURCE_KB, ResourceAccessService.RESOURCE_AGENT);

    private final ResourceAccessService resourceAccessService;

    /**
     * 授权：给某角色 / 用户授予对某资源的四权（已存在则更新，幂等，不破坏唯一键 uk_principal_resource）。
     */
    @PostMapping
    public Result<Void> grant(@RequestBody ResourceAccessRequest req) {
        validate(req);
        resourceAccessService.requireManager(req.getResourceType(), req.getResourceId());
        resourceAccessService.grant(req.getPrincipalType(), req.getPrincipalId(), req.getResourceType(),
                req.getResourceId(), req.isCanRead(), req.isCanWrite(), req.isCanUse(), req.isCanEdit());
        return Result.ok();
    }

    /**
     * 撤销授权：删掉某主体对某资源的授权行。
     */
    @DeleteMapping
    public Result<Void> revoke(@RequestBody ResourceAccessRequest req) {
        validate(req);
        resourceAccessService.requireManager(req.getResourceType(), req.getResourceId());
        resourceAccessService.revoke(req.getPrincipalType(), req.getPrincipalId(), req.getResourceType(), req.getResourceId());
        return Result.ok();
    }

    /**
     * 列出某资源当前的全部授权（管理者可见）。
     */
    @GetMapping
    public Result<List<ResourceAccessView>> list(@RequestParam String resourceType, @RequestParam Long resourceId) {
        resourceAccessService.requireManager(resourceType, resourceId);
        return Result.ok(resourceAccessService.listGrants(resourceType, resourceId));
    }

    /** 参数合法性校验：principalType/principalId/resourceType/resourceId 必填且在允许集合内。 */
    private void validate(ResourceAccessRequest req) {
        if (req == null
                || req.getPrincipalType() == null || !PRINCIPAL_TYPES.contains(req.getPrincipalType())
                || req.getPrincipalId() == null || req.getPrincipalId().isBlank()
                || req.getResourceType() == null || !RESOURCE_TYPES.contains(req.getResourceType())
                || req.getResourceId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "principalType/principalId/resourceType/resourceId 非法");
        }
    }
}
