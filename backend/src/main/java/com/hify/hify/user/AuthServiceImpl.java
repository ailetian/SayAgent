package com.hify.hify.user;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.security.JwtUtil;
import com.hify.hify.user.dto.LoginRequest;
import com.hify.hify.user.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录业务实现（§3.4 分层纪律：真正的比对、签发逻辑都在这里）。
 *
 * <p>大白话：{@code login()} 干三件事——
 * <ol>
 *   <li>按用户名查用户（只查未删除的，{@code deleted=false}）；</li>
 *   <li>查不到、或明文密码对不上密文 → 抛 {@code BizException(AUTH_FAIL)}（最终翻译成 HTTP 401）；</li>
 *   <li>比对通过 → 用 {@link JwtUtil} 签一张带「用户名 + 角色」的 JWT，装进 {@link LoginResponse} 回给前端。</li>
 * </ol>
 *
 * <p>分层边界：本类不碰 HTTP、不碰 Spring Security 的认证上下文；只做业务判定和凭证签发。
 * {@code userDetailsService} 暂时留着（未来 {@code @PreAuthorize} 角色闸会用到），本任务不调用。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final CustomUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsernameAndDeletedFalse(request.username())
                .orElseThrow(() -> new BizException(ErrorCode.AUTH_FAIL, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.AUTH_FAIL);
        }
        String token = jwtUtil.sign(user.getUsername(), user.getRole().name());
        return new LoginResponse(token, user.getUsername(), user.getRole().name());
    }
}
