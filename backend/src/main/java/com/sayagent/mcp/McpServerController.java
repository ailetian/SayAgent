package com.sayagent.mcp;

import com.sayagent.common.Result;
import com.sayagent.mcp.dto.McpServerCreateReq;
import com.sayagent.mcp.dto.McpServerVO;
import com.sayagent.mcp.McpServerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP Server 配置管理接口「前台柜员」（M7/T1，§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/mcp/servers} 一组入口。自己不写业务逻辑：
 * <ul>
 *   <li>鉴权第一道闸由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 兜底——未登录直接 401；</li>
 *   <li>增删改的 ADMIN 权限在服务层 {@link McpServerService} 再核（§7.11）；</li>
 *   <li>所有响应统一包 {@link Result}；失败由 {@code GlobalExceptionHandler} 翻译。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp/servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping
    public Result<List<McpServerVO>> listServers() {
        return Result.ok(mcpServerService.listServers());
    }

    @GetMapping("/{id}")
    public Result<McpServerVO> getServer(@PathVariable Long id) {
        return Result.ok(mcpServerService.getServer(id));
    }

    @PostMapping
    public Result<McpServerVO> createServer(@Valid @RequestBody McpServerCreateReq request) {
        return Result.ok(mcpServerService.createServer(request));
    }

    @PutMapping("/{id}")
    public Result<McpServerVO> updateServer(@PathVariable Long id,
                                            @Valid @RequestBody McpServerCreateReq request) {
        return Result.ok(mcpServerService.updateServer(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteServer(@PathVariable Long id) {
        mcpServerService.deleteServer(id);
        return Result.ok();
    }
}
