package com.sayagent.mcp;

import com.sayagent.common.base.BaseRepository;

/**
 * MCP Server 配置仓储（M7/T1，§3.2 后端包结构）。
 *
 * <p>大白话：把对 {@code mcp_server} 表的增删改查交给 Spring Data；继承 {@link BaseRepository}
 * 白捡 JPA 通用能力，软删除过滤由实体上的 {@code @SQLRestriction} 自动生效。
 */
public interface McpServerRepository extends BaseRepository<McpServer> {
}
