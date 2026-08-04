package com.hify.hify.user;

/**
 * 用户角色：决定能进哪些门（§2 模块1 轻量权限）。
 * 存库用字符串 "ADMIN"/"USER"（@Enumerated STRING），便于人读与排错。
 */
public enum UserRole {
    ADMIN,
    USER
}
