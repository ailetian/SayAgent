package com.sayagent.user;

/**
 * 用户角色：决定能进哪些门（§2.1 角色与权限模型）。
 * 三档：ADMIN 平台管理员 / OPERATOR 知识运营 / USER 普通成员。
 * 存库用字符串 "ADMIN"/"OPERATOR"/"USER"（@Enumerated STRING），便于人读与排错。
 */
public enum UserRole {
    ADMIN,
    OPERATOR,
    USER
}
