package com.hify.hify.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Pg 向量库（第二数据源）的 DataSource 与 JdbcTemplate 定义。
 *
 * <p>用 {@link HikariDataSource} + {@code @ConfigurationProperties("spring.datasource.pg")} 构建，
 * yml 里 {@code spring.datasource.pg.jdbc-url}（Hikari 风格）能正确绑定到 jdbcUrl，
 * 避免此前用裸 {@code DataSourceBuilder} + 错误前缀时 jdbcUrl 为空、迁移/连接报 "jdbcUrl is required" 的坑。
 * 表结构迁移由 {@link PgSchemaMigrator} 负责，这里只提供连接与 JdbcTemplate。
 */
@Configuration
public class PgDataSourceConfig {

    @Bean(name = "pgDataSource")
    @ConfigurationProperties("spring.datasource.pg")
    public DataSource pgDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }

    /**
     * 命名参数模板（构建在 {@code pgJdbcTemplate} 之上），供需要 {@code IN (:list)} 与命名参数的检索 SQL 使用
     * （K4 混合检索）。命名参数 + {@code IN (:list)} 自动展开，既防 SQL 注入（§7.2）又避开变参 arity 带来的测试麻烦。
     */
    @Bean(name = "pgNamedJdbcTemplate")
    public NamedParameterJdbcTemplate pgNamedJdbcTemplate(@Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate) {
        return new NamedParameterJdbcTemplate(pgJdbcTemplate);
    }

    /**
     * Pg 向量库（第二数据源）的事务管理器（K11 缺陷 B）。
     *
     * <p>大白话：pg 是独立于 MySQL 的第二数据源，裸 JDBC 默认自动提交，事务不可达。
     * 这里显式建一个 {@code pgTransactionManager}，让 {@code DocumentChunkRepository.replaceChunks}
     * （删旧切片 + 插新切片）能在一个 pg 本地事务里原子完成，避免崩溃残留"半套 chunk"。
     */
    @Bean(name = "pgTransactionManager")
    public PlatformTransactionManager pgTransactionManager(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new DataSourceTransactionManager(pgDataSource);
    }
}
