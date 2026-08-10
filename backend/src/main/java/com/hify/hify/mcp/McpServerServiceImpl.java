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
 *   <li>读（list/get）任何已登录用户可用；写（create/update/delete）仅 ADMIN（§7.11 服务层再核）；</li>
 *   <li>对外一律返回 {@link McpServerVO}；{@code address} 是内部服务地址非秘钥，可返（§7.11 规则37）；</li>
 *   <li>找不到记录抛 {@code MCP_SERVER_NOT_FOUND}；非 ADMIN 写操作抛 {@code FORBIDDEN}（§7.3）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private final McpServerRepository repository;

    /** 列出全部 MCP Server（软删除已由 @SQLRestriction 过滤）。 */
    @Override
    public List<McpServerVO> listServers() {
        return repository.findAll().stream()
                .map(McpServerVO::from)
                .toList();
    }

    /** 查看单个 MCP Server；不存在抛 MCP_SERVER_NOT_FOUND。 */
    @Override
    public McpServerVO getServer(Long id) {
        return McpServerVO.from(findById(id));
    }

    /** 新增 MCP Server（仅 ADMIN）；status 缺省置 1（启用）。 */
    @Override
    public McpServerVO createServer(McpServerCreateReq req) {
        assertAdmin();
        McpServer s = new McpServer();
        s.setName(req.name());
        s.setAddress(req.address());
        s.setType(req.type());
        s.setStatus(req.status() != null ? req.status() : McpServerStatus.ENABLED);
        return McpServerVO.from(repository.save(s));
    }

    /** 修改 MCP Server（仅 ADMIN）；按请求字段整体更新。 */
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
