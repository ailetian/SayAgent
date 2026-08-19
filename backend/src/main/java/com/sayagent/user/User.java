package com.sayagent.user;

import com.sayagent.common.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 系统用户（员工花名册的一行）。密码字段只存 BCrypt 密文，绝不序列化返前端（§7.11）。
 * 四字段 id/created_at/updated_at/deleted 由 BaseEntity 提供（§6.1）；
 * 软删过滤由本类 {@code @SQLRestriction}/{@code @SQLDelete} 显式实现（坑位5：不可放 BaseEntity）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `user` SET deleted = 1 WHERE id = ?")
@Entity
@Table(name = "user", uniqueConstraints = {
        @UniqueConstraint(name = "uk_username", columnNames = {"username", "deleted"})
})
public class User extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String username;

    /** BCrypt 密文（60 位左右），明文永不入库、永不返前端。 */
    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    /** 显示名（V31 补列，§2.1 支撑 POST /api/users 契约；可空）。 */
    @Column(length = 64)
    private String displayName;

    /** 邮箱（V31 补列，可空）。 */
    @Column(length = 128)
    private String email;
}
