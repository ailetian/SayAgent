package com.hify.hify.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 门口闸机：每个请求先过它。
 * 从 Authorization: Bearer <token> 取卡 → JwtUtil 验卡 → 验过就在现场记下身份放行。
 *
 * <p>行为约定（与 SecurityConfig 配合）：
 * - 有 token 且合法 → 写入身份放行；
 * - 有 token 但非法/过期 → 返 401，绝不放行；
 * - 无 token（无 Authorization 头或非 Bearer）→ 不放行也不拦，直接透传给后续过滤器，
 *   最终由 SecurityConfig 的 authorizeHttpRequests 裁决：permitAll 路径（如登录接口）直接通过，
 *   受保护路径由 Spring Security 的 AuthorizationFilter 返 401。
 * （§7.11：权限在服务层也要再核；登录接口本身本就没有 token，故闸机对"无卡"必须放行。）
 */
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // 无 token：放行，授权判定交给 SecurityConfig 的 authorizeHttpRequests
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            String username = jwtUtil.parseUsername(token);
            String role = jwtUtil.parseRole(token);
            var auth = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 校验失败: {}", e.getMessage());
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "token 非法或已过期");
        }
    }
}
