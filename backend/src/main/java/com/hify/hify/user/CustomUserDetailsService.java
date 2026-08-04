package com.hify.hify.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保安翻花名册：报用户名 → 去 user 表取一行 → 包装成 Spring 的 UserDetails（含角色）。
 * 本类归属 user 业务包（按账号查人是 User 域逻辑），与 User/UserRepository 同包，
 * 不再造成 common → user 的反向依赖（§3.2）。只做"加载"，不比对密码。
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        // 角色前缀 ROLE_ 是 Spring Security 的约定；@PreAuthorize("hasRole('ADMIN')") 会比对 ROLE_ADMIN
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                List.of(authority)
        );
    }
}
