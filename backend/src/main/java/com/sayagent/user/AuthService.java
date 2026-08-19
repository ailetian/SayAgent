package com.sayagent.user;

import com.sayagent.user.dto.LoginRequest;
import com.sayagent.user.dto.LoginResponse;

/**
 * 登录业务接口（§3.4 分层纪律：Controller 只调接口，实现下沉到 service）。
 *
 * <p>大白话：这是「登录」这件事对外的门面——Controller 不关心密码怎么比对、token 怎么签，
 * 只管把请求转交给 {@code login()} 拿回一个 {@link LoginResponse}。
 */
public interface AuthService {

    /**
     * 校验用户名/密码，成功返回带 JWT 的登录回执。
     *
     * @param request 登录请求（用户名 + 明文密码）
     * @return 登录成功回执（token + 用户名 + 角色）
     * @throws com.sayagent.common.exception.BizException 用户名不存在或密码错误时，错误码 {@code AUTH_FAIL}
     */
    LoginResponse login(LoginRequest request);
}
