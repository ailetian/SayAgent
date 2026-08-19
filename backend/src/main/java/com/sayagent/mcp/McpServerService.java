package com.sayagent.mcp;

import com.sayagent.mcp.dto.McpServerCreateReq;
import com.sayagent.mcp.dto.McpServerVO;

import java.util.List;

/**
 * MCP Server 配置服务接口（M7/T1，§7.1 规则4 接口命名 + §3.2 跨模块只依赖接口）。
 *
 * <p>大白话：对外发布「MCP Server 通讯录」的增删改查能力。下游 {@code conversation} 模块（T3）
 * 将只依赖本接口取配置，不触碰 {@code mcp} 包内部实体/仓储。
 *
 * <p>权限约束：读（list/get）任意已登录用户可用；写（create/update/delete）仅 ADMIN，
 * 在 {@code McpServerServiceImpl} 服务层再核（§7.11 规则38）。
 */
public interface McpServerService {

    /** 列出全部 MCP Server（软删除已由 @SQLRestriction 过滤）。 */
    List<McpServerVO> listServers();

    /** 查看单个 MCP Server；不存在抛 MCP_SERVER_NOT_FOUND。 */
    McpServerVO getServer(Long id);

    /** 新增 MCP Server（仅 ADMIN）。 */
    McpServerVO createServer(McpServerCreateReq req);

    /** 修改 MCP Server（仅 ADMIN）。 */
    McpServerVO updateServer(Long id, McpServerCreateReq req);

    /** 停用（软删除）MCP Server（仅 ADMIN）。 */
    void deleteServer(Long id);
}
