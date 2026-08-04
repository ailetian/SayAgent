package com.hify.hify.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 主数据源（MySQL）配置。
 *
 * <p>大白话：MySQL 是主库（@Primary），应用启动时由 Spring 自己的 Flyway 自动配置跑 db/mysql/migration。
 * Pg 向量库（第二数据源）的 DataSource 见 {@link PgDataSourceConfig}，其表结构迁移由
 * {@link PgSchemaMigrator} 在应用启动后（ApplicationRunner）执行，满足 CLAUDE.md §9「表结构变更走 Flyway」。
 */
@Configuration
public class PgVectorConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource(DataSourceProperties properties) {
        // 必须用 DataSourceProperties.initializeDataSourceBuilder() 来构建 HikariDataSource：
        // HikariDataSource 只有 setJdbcUrl、没有 setUrl，若直接 new HikariDataSource() + @ConfigurationProperties，
        // yml 的 spring.datasource.url 无法映射到 jdbcUrl，会报 "jdbcUrl is required with driverClassName"
        // （且会让 @DataJpaTest / @SpringBootTest 因缺 jdbcUrl 而加载失败）。
        // initializeDataSourceBuilder() 会把标准 url 正确写入 jdbcUrl，同时兼容自动配置、@DataJpaTest、@SpringBootTest 与 java -jar。
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
