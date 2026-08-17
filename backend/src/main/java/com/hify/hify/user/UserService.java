package com.hify.hify.user;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.user.dto.CreateUserRequest;
import com.hify.hify.user.dto.UserVO;

import java.util.List;

/**
 * 用户服务发布接口（§3.2 跨模块解耦）。
 *
 * <p>conversation 等其它模块只依赖 {@link #resolveUserId(String)} 把「登录名 → 用户主键」解析出来，
 * 禁止直接 import user.UserRepository 或 user.User 实体。M9/T3 在此接口上扩展
 * {@link #createUser(CreateUserRequest)} 与 {@link #listUsers()}，供新建 {@code UserController} 调用。</p>
 */
public interface UserService {

    /**
     * 解析登录名到用户主键；用户不存在或已软删时抛 {@link ErrorCode#UNAUTHORIZED}。
     *
     * @param username 登录名（SecurityContext 中的 principal）
     * @return 用户主键 id
     */
    Long resolveUserId(String username);

    /**
     * 新建用户（仅 ADMIN，非 ADMIN 抛 {@link ErrorCode#FORBIDDEN}）。密码以 BCrypt 密文落库，
     * 明文永不入库（§7.11）。username 重复抛 {@link ErrorCode#USERNAME_EXISTS}。
     *
     * @param request 建用户请求（用户名/密码/角色/显示名/邮箱）
     * @return 新建用户的脱敏视图（不含 password）
     */
    UserVO createUser(CreateUserRequest request);

    /**
     * 列出全部未软删用户（脱敏视图，不含 password）。
     *
     * @return 用户视图列表
     */
    List<UserVO> listUsers();
}
