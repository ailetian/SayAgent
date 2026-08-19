package com.sayagent.user;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.user.dto.CreateUserRequest;
import com.sayagent.user.dto.UserVO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService（M9/T3）单测：覆盖建用户 BCrypt 落库 / 默认角色 / 非 ADMIN 403 / 重名 / 列表脱敏。
 * 用真实 {@link BCryptPasswordEncoder} 验证密文可匹配明文；判权走 T2 {@link com.sayagent.common.security.AuthContext}。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserServiceImpl userService;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void asUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void createUser_asAdmin_bcryptAndReturnsVoWithoutPassword() {
        asAdmin();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.findByUsernameAndDeletedFalse("alice")).thenReturn(Optional.empty());
        User saved = new User();
        saved.setId(10L);
        saved.setUsername("alice");
        saved.setRole(UserRole.OPERATOR);
        saved.setPassword("$2a$placeholder");
        saved.setDisplayName("Alice");
        saved.setEmail("a@x.com");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        UserVO vo = userService.createUser(
                new CreateUserRequest("alice", "secret123", UserRole.OPERATOR, "Alice", "a@x.com"));

        assertEquals("alice", vo.username());
        assertEquals(UserRole.OPERATOR, vo.role());
        assertNull(vo.password());
        assertEquals("Alice", vo.displayName());
        assertEquals("a@x.com", vo.email());
        // 验证实际入库的是 BCrypt 密文且能匹配明文（§7.11）
        verify(userRepository).save(argThat(u -> u.getPassword() != null
                && u.getPassword().startsWith("$2a$")
                && passwordEncoder.matches("secret123", u.getPassword())));
    }

    @Test
    void createUser_defaultRoleIsUser() {
        asAdmin();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.findByUsernameAndDeletedFalse("carol")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserVO vo = userService.createUser(new CreateUserRequest("carol", "pw123456", null, null, null));
        assertEquals(UserRole.USER, vo.role());
    }

    @Test
    void createUser_asUser_throwsForbidden() {
        asUser();
        userService = new UserServiceImpl(userRepository, passwordEncoder);

        BizException ex = assertThrows(BizException.class,
                () -> userService.createUser(new CreateUserRequest("eve", "pw123456", UserRole.USER, null, null)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_duplicate_throwsUsernameExists() {
        asAdmin();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.findByUsernameAndDeletedFalse("alice")).thenReturn(Optional.of(new User()));

        BizException ex = assertThrows(BizException.class,
                () -> userService.createUser(new CreateUserRequest("alice", "pw123456", UserRole.USER, null, null)));
        assertEquals(ErrorCode.USERNAME_EXISTS, ex.getErrorCode());
    }

    @Test
    void listUsers_noPasswordInVo() {
        asAdmin();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        User u = new User();
        u.setUsername("alice");
        u.setRole(UserRole.OPERATOR);
        u.setPassword("$2a$enc");
        when(userRepository.findAll()).thenReturn(List.of(u));

        List<UserVO> list = userService.listUsers();
        assertEquals(1, list.size());
        assertNull(list.get(0).password());
    }

    @Test
    void requireAdmin_doesNotThrowForAdmin() {
        asAdmin();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.findByUsernameAndDeletedFalse("z")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(new User());
        assertDoesNotThrow(() -> userService.createUser(new CreateUserRequest("z", "pw123456", null, null, null)));
    }
}
