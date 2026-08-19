package com.sayagent.user;

import com.sayagent.common.base.BaseRepository;
import java.util.Optional;

/**
 * 用户数据访问。软删除由 {@code User} 实体上的 {@code @SQLRestriction}/{@code @SQLDelete} 实现（§6.1，坑位5）；
 * BaseRepository 仅提供通用 JpaRepository 能力，不自动软删。
 * 派生查询：按用户名查"未删除"的用户（§6.2 含 deleted）。
 */
public interface UserRepository extends BaseRepository<User> {

    Optional<User> findByUsernameAndDeletedFalse(String username);
}
