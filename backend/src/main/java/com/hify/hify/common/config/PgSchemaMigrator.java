package com.hify.hify.common.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Pg 向量库 Flyway 迁移触发（M5 T1，§9 合规）。
 *
 * <p>大白话：应用启动完成（ApplicationContext 刷新完毕）后，用 Flyway 跑 db/pg/migration，
 * 把 document_chunk 表 / 索引 / HNSW 落到 pg 向量库。放在 ApplicationRunner 里（而非 @PostConstruct），
 * 避免迁移时机与 MySQL Flyway / 主数据源 Bean 创建互相阻塞、导致整个上下文启动失败。
 *
 * <p>pg 不可达或配置缺失时只记错误日志、不抛异常——检索会在运行时以 BizException(RETRIEVAL_FAILED) 暴露，
 * 这样无需真实 pg 也能启动（如 KnowledgeControllerAuthTest 用 @MockBean 规避真实建库的场景）。
 */
@Slf4j
@Component
public class PgSchemaMigrator implements ApplicationRunner {

    private final DataSource pgDataSource;

    public PgSchemaMigrator(@Qualifier("pgDataSource") DataSource pgDataSource) {
        this.pgDataSource = pgDataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(pgDataSource)
                    .locations("classpath:db/pg/migration")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            log.info("Pg 向量库 Flyway 迁移完成：document_chunk 表 / 索引就绪（HNSW 按 §6.6 暂缓已移除，检索走精确扫描）");
        } catch (Exception e) {
            log.error("Pg 向量库 Flyway 迁移未执行（pg 不可达或配置缺失），检索将在运行时以 RETRIEVAL_FAILED 暴露", e);
        }
    }
}
