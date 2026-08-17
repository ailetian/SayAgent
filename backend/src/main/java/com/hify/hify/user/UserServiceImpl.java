package com.hify.hify.user;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.security.AuthContext;
import com.hify.hify.user.dto.CreateUserRequest;
import com.hify.hify.user.dto.UserVO;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UserService 默认实现：走 user 模块内部的 UserRepository（内部类不外泄）。
 *
 * <p>守卫约定（§2.1/§7.11）：写操作在服务层手写 {@code requireAdmin()}，非 ADMIN 抛
 * {@code BizException(ErrorCode.FORBIDDEN)}（{@code @PreAuthorize} 实际未生效，靠手写，
 * 与 AgentService / ModelService / McpServerServiceImpl 一致）；{@code requireAdmin} 复用 T2 的
 * {@link AuthContext#isAdmin()}，不新造鉴权机制。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Long resolveUserId(String username) {
        return userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED,
                        "用户不存在或已软删: " + username))
                .getId();
    }

    @Override
    public UserVO createUser(CreateUserRequest request) {
        requireAdmin();
        if (userRepository.findByUsernameAndDeletedFalse(request.username()).isPresent()) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        UserRole role = request.role() != null ? request.role() : UserRole.USER;
        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));   // BCrypt 密文落库，明文不过 persist 层
        user.setRole(role);
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        User saved = userRepository.save(user);
        return toVO(saved);
    }

    @Override
    public List<UserVO> listUsers() {
        return userRepository.findAll().stream()
                .map(this::toVO)
                .toList();
    }

    private UserVO toVO(User user) {
        // password 显式传 null + @JsonIgnore 双保险，确保响应永不带密文（§7.11）
        return new UserVO(user.getUsername(), user.getRole(), user.getDisplayName(), user.getEmail(), null);
    }

    /** 服务层权限再核（§2.1/§7.11）：复用 T2 统一身份工具 {@link AuthContext#isAdmin()}，非 ADMIN 抛 FORBIDDEN。 */
    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可管理用户");
        }
    }
}
