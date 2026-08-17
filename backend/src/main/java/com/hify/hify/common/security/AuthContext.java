package com.hify.hify.common.security;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一身份工具（M9/T2）。
 *
 * <p>大白话：全系统现在散着取"当前登录人是谁、是不是管理员、有哪些角色"，
 * 新建一个统一工具，谁要判权都从它拿，避免各模块各写一套 {@code SecurityContext}
 * 解析导致行为不一致（审查报告 P1-5 修复）。
 *
 * <p>本类行为刻意与 {@code com.hify.hify.knowledge.service.KbAccessGuard}
 * 的 {@code currentUser()}/{@code isAdmin()} 完全一致：
 * <ul>
 *   <li>principal = username（{@code AuthFilter} 把 username 写入 {@code SecurityContext} 的 principal）；</li>
 *   <li>admin = authorities 含 {@code ROLE_ADMIN}；</li>
 *   <li>角色集合 = authorities 去除 {@code ROLE_} 前缀。</li>
 * </ul>
 *
 * <p>设计为静态工具、不依赖 Spring 容器：
 * <ol>
 *   <li>各业务包（T3–T7）按需直接 {@code AuthContext.currentUsername()} 调用，无需注入 bean；</li>
 *   <li>单测可直接操作 {@code SecurityContextHolder}，无需起 Spring 上下文。</li>
 * </ol>
 *
 * <p>异常统一用 {@link BizException}/{@link ErrorCode}（§7.3/§3.5），不复造 {@code AppException}/{@code BizCode} 等不存在的类。
 */
public final class AuthContext {

    /** Spring Security 角色权威前缀（{@code AuthFilter} 写入 {@code ROLE_}+角色）。 */
    private static final String ROLE_PREFIX = "ROLE_";

    private AuthContext() {
    }

    /**
     * 取当前登录用户名（{@code AuthFilter} 将 username 写入 {@code SecurityContext} principal）。
     *
     * <p>无认证或身份为空时抛 {@link ErrorCode#UNAUTHORIZED}，与 {@code KbAccessGuard.currentUser()} 一致。
     */
    public static String currentUsername() {
        Authentication auth = authentication();
        return auth.getName();
    }

    /** 当前用户是否管理员（authorities 含 {@code ROLE_ADMIN}），与 {@code KbAccessGuard.isAdmin()} 一致。 */
    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * 当前用户角色集合（去除 {@code ROLE_} 前缀，如 {@code ADMIN}/{@code OPERATOR}/{@code USER}）。
     *
     * <p>无认证或权威为空时返回空集合（不抛异常，便于调用方直接判 {@code roleSet().contains(...)}）。
     */
    public static Set<String> roleSet() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return auth;
    }
}
