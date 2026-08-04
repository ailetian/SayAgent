package com.hify.hify.common.base;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 所有数据库实体共用的「通用户口本」（M1/T3）。
 *
 * <p>大白话：像公司给每张表发一本统一格式的户口簿——不管是什么业务表，
 * 都自动拥有 {@code id}（主键）、{@code createdAt}（创建时间）、
 * {@code updatedAt}（更新时间）、{@code deleted}（软删除标记）这四个字段，
 * 业务实体只需 {@code extends BaseEntity} 即可免费获得这四个字段、不用每张表手写一遍（§6.1）。
 *
 * <p><b>注意</b>：{@code deleted} 字段虽在此声明，但软删除「查询过滤（不加已删行）+ 删除转 UPDATE」
 * <b>不能</b>放在本类——{@code @MappedSuperclass} 上的注解（{@code @SQLRestriction}/@Where/@SQLDelete）
 * <b>不会传播</b>到子类查询（代码审核坑位5）。每个含 {@code deleted} 的 {@code @Entity} 必须<b>显式</b>加
 * {@code @SQLRestriction("deleted = 0")} 与 {@code @SQLDelete(...)}（见 §6.1 实现纪律）。
 *
 * <p>{@code @MappedSuperclass} 表示本类本身不建表，它的字段会「拷贝」到每个子类对应的表里。
 */
@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor
public abstract class BaseEntity {

    /** 主键：BIGINT 自增（§6.1 禁用 UUID 主键，防索引碎片）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建时间：插入时固定，之后不可改（{@code updatable = false}）。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 更新时间：每次保存时由 {@link #touch()} 刷新为当前时间。 */
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * 软删除标记：0=未删，1=已删。
     * 布尔变量不使用 is 前缀（§7.1 规则 5）；默认未删（§6.1）。
     */
    @Column(columnDefinition = "tinyint(1) default 0")
    private Boolean deleted = false;

    /**
     * 写入前自动维护时间戳（JPA 不会自动刷新 {@code updatedAt}）。
     * 首次插入时补创建时间，每次更新时刷新更新时间。
     */
    @PrePersist
    @PreUpdate
    protected void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }
}
