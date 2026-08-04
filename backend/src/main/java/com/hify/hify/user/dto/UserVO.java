package com.hify.hify.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hify.hify.user.UserRole;

/**
 * 用户对外视图对象（View Object，§3.4 分层纪律：Controller 只往外吐 VO，不吐实体）。
 *
 * <p>大白话：将来「我的资料」这类接口要返回用户信息时，用这个盒子代替 {@code User} 实体，
 * 目的只有一个——<b>绝不让密码（哪怕是 BCrypt 密文）跟着响应走到前端</b>。
 *
 * <p>做法：{@code password} 字段打 {@code @JsonIgnore}，Jackson 序列化时直接跳过它；
 * 即使以后不小心填了值，前端也拿不到。
 *
 * @param username 账号
 * @param role     角色
 * @param password 密文密码（仅用于后端内部构造，序列化时丢弃）
 */
public record UserVO(String username, UserRole role, @JsonIgnore String password) {
}
