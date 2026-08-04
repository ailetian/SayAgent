package com.hify.hify.user.dto;

/**
 * 登录成功响应「回执」（§3.5 API 响应契约）。
 *
 * <p>大白话：登录成功后，后端把「凭证」装进这个盒子回给前端：
 * <ul>
 *   <li>{@code token}：JWT 字符串，前端之后每次请求塞进 {@code Authorization: Bearer <token>} 头。</li>
 *   <li>{@code username}：登录账号，方便前端直接展示。</li>
 *   <li>{@code role}：角色名（USER/ADMIN），前端据此做粗粒度菜单显隐。</li>
 * </ul>
 *
 * <p>严禁把密文密码塞进来（§7.11 敏感字段不明文/不随响应外泄）。
 *
 * @param token    JWT 凭证
 * @param username 登录账号
 * @param role     角色名
 */
public record LoginResponse(String token, String username, String role) {
}
