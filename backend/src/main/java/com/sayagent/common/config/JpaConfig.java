package com.sayagent.common.config;

import java.util.Map;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * JPA 全局配置（§3.2 common 包，M1/T5）。
 *
 * <p>大白话：这是给 Hibernate（JPA 的"翻译官"，负责把 Java 对象变成 SQL）下的几条全局规矩，
 * 让所有表的批量写入更高效、SQL 更可控，属于"地基级"统一设置，业务代码无需关心。
 *
 * <p>注意：仅设全局行为，绝不在这里自动建表（建表一律走 Flyway，§9 硬规则）。
 */
@Configuration
public class JpaConfig implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        // 批量写入/更新时合并 SQL，减少与数据库往返次数（高并发落库有用，§8 性能瓶颈#6）
        hibernateProperties.put("hibernate.jdbc.batch_size", "50");
        hibernateProperties.put("hibernate.order_inserts", "true");
        hibernateProperties.put("hibernate.order_updates", "true");
        // 避免部分驱动在事务外创建 Lob 导致连接泄漏
        hibernateProperties.put("hibernate.jdbc.lob.non_contextual_creation", "true");
    }
}
