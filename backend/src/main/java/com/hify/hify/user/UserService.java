package com.hify.hify.user;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

/**
 * 用户解析发布接口（M6 T3，§3.2 跨模块解耦）。
 *
 * <p>conversation 等其它模块只依赖此接口把「登录名 → 用户主键」解析出来，
 * 禁止直接 import user.UserRepository 或 user.User 实体。</p>
 */
public interface UserService {

    /**
     * 解析登录名到用户主键；用户不存在或已软删时抛 {@link ErrorCode#UNAUTHORIZED}。
     *
     * @param username 登录名（SecurityContext 中的 principal）
     * @return 用户主键 id
     */
    Long resolveUserId(String username);
}
