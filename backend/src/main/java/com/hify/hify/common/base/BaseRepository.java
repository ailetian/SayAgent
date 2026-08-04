package com.hify.hify.common.base;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 所有业务仓储的「公共父类接口」（M1/T3）。
 *
 * <p>大白话：把「增删改查」这类每个表都需要的通用仓库操作抽到一个模板里，
 * 业务仓储只写 {@code extends BaseRepository<User>} 就白捡了 JPA 的全部能力，
 * 不用每个仓储都重复声明（§3.2 后端包结构）。
 *
 * <p>{@code @NoRepositoryBean} 告诉 Spring Data：这是一个「模板接口」，
 * 不要为它单独造一个 Bean；只有具体业务仓储（如 {@code UserRepository}）才会被实例化。
 *
 * @param <T> 业务实体类型，必须继承 {@link BaseEntity}
 */
@NoRepositoryBean
public interface BaseRepository<T extends BaseEntity> extends JpaRepository<T, Long> {
}
