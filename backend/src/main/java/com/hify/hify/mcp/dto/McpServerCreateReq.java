package com.hify.hify.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建/修改 MCP Server 请求（M7/T1，§3.5 入参契约）。
 *
 * <p>大白话：管理员填一张「登记内部系统」的表。带 {@code @NotBlank}/{@code @NotNull} 的是硬必填；
 * {@code type} 用 {@code @Pattern} 限定只能是 STDIO/SSE/HTTP；{@code status} 1=启用 0=停用。
 * 新增与修改共用本记录（配置表单整体提交）。
 */
public record McpServerCreateReq(

        @NotBlank(message = "名称必填")
        @Size(max = 64, message = "名称不超过64字符")
        String name,

        @NotBlank(message = "地址必填")
        @Size(max = 255, message = "地址不超过255字符")
        String address,

        @NotBlank(message = "类型必填")
        @Pattern(regexp = "STDIO|SSE|HTTP", message = "类型只能为 STDIO/SSE/HTTP")
        String type,

        @NotNull(message = "状态必填")
        Integer status) {
}
