package com.hify.hify.mcp;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.mcp.dto.McpServerCreateReq;
import com.hify.hify.mcp.dto.McpServerVO;
import com.hify.hify.mcp.McpServer;
import com.hify.hify.mcp.McpServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP Server 配置服务实现（M7/T1，§3.4 分层纪律：Controller 只调本服务，真正增删改查在这里）。
 *
 * <p>大白话：给管理员一套管「内部系统通讯录」的接口——列出、查看、新增、修改、停用。
 * <ul>
 *   <li>读（list/get）与写（create/update/delete）均仅 ADMIN（§7.11 服务层再核；M10/T2 给读接口加 assertAdmin 锁管理员）；</li>
 *   <li>对外一律返回 {@link McpServerVO}；{@code address} 是内部服务地址非秘钥，可返（§7.11 规则37）；</li>
 *   <li>找不到记录抛 {@code MCP_SERVER_NOT_FOUND}；非 ADMIN 写操作抛 {@code FORBIDDEN}（§7.3）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private final McpServerRepository repository;

    /** 列出全部 MCP Server（软删除已由 @SQLRestriction 过滤）；仅 ADMIN 可见（M10/T2 读接口加 assertAdmin）。 */
    @Override
    public List<McpServerVO> listServers() {
        assertAdmin();
        return repository.findAll().stream()
                .map(McpServerVO::from)
                .toList();
    }

    /** 查看单个 MCP Server；仅 ADMIN 可见（M10/T2 读接口加 assertAdmin）；不存在抛 MCP_SERVER_NOT_FOUND。 */
    @Override
    public McpServerVO getServer(Long id) {
        assertAdmin();
        return McpServerVO.from(findById(id));
    }

    /** 新增 MCP Server（仅 ADMIN）；status 缺省置 1（启用）；authType/authConfig 一并落库（M10/T1 鉴权）。 */
    @Override
    public McpServerVO createServer(McpServerCreateReq req) {
        assertAdmin();
        McpServer s = new McpServer();
        s.setName(req.name());
        s.setAddress(req.address());
        s.setType(req.type());
        s.setStatus(req.status() != null ? req.status() : McpServerStatus.ENABLED);
        s.setAuthType(req.authType());
        s.setAuthConfig(req.authConfig()); // 敏感字段，仅落库（DB），绝不进 McpServerVO（§7.11）
        s.setDataSensitivity(normalizeDataSensitivity(req.dataSensitivity())); // 分类标签(M10/T4)可返前端；缺省/空→INTERNAL（DB default 仅在列被省略时生效，显式 set null 会触发 NOT NULL，故此处兜底）
        return McpServerVO.from(repository.save(s));
    }

    /** 修改 MCP Server（仅 ADMIN）；按请求字段整体更新，含鉴权配置（M10/T1）。 */
    @Override
    public McpServerVO updateServer(Long id, McpServerCreateReq req) {
        assertAdmin();
        McpServer s = findById(id);
        s.setName(req.name());
        s.setAddress(req.address());
        s.setType(req.type());
        if (req.status() != null) {
            s.setStatus(req.status());
        }
        s.setAuthType(req.authType());
        s.setAuthConfig(req.authConfig());
        s.setDataSensitivity(normalizeDataSensitivity(req.dataSensitivity())); // 分类标签(M10/T4)可返前端；缺省/空→INTERNAL 兜底
        return McpServerVO.from(repository.save(s));
    }

    /** 停用（软删除）MCP Server（仅 ADMIN）；实际置 deleted=1，不真删（§6.1 软删纪律）。 */
    @Override
    public void deleteServer(Long id) {
        assertAdmin();
        McpServer s = findById(id);
        repository.delete(s);
    }

    /** 按 id 查配置，不存在抛 MCP_SERVER_NOT_FOUND（§3.5 错误码统一枚举）。 */
    private McpServer findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MCP_SERVER_NOT_FOUND, "id=" + id));
    }

    /** M10/T4：dataSensitivity 缺省/空 → INTERNAL（DB 列 NOT NULL 且 default 仅在列省略时生效，故在代码层兜底，避免显式 set null 触发 NOT NULL 约束异常）。 */
    private static String normalizeDataSensitivity(String ds) {
        return (ds == null || ds.isBlank()) ? "INTERNAL" : ds;
    }

    /** 服务层权限再核（§7.11）：当前登录用户须为 ROLE_ADMIN，否则 FORBIDDEN（HTTP 403）。 */
    private void assertAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可增删改 MCP Server 配置");
        }
    }
}
