package com.hify.hify.knowledge.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * zhparser（PostgreSQL 中文分词扩展）可用性探针（K2，与 K1 迁移 {@code V4__enable_zhparser.sql} 方案 A 配套）。
 *
 * <p>大白话：开机自检一句"这台 PG 会不会中文分词"。
 * <ul>
 *   <li>会 → 全文检索用 {@code zhparser_cfg}，中文按词切，"离职/辞职"这种换说法也能命中。</li>
 *   <li>不会 → <b>不关全文检索</b>，只把分词器降级成 PG 内置的 {@code simple}（按字切，召回略差），并打 WARN。</li>
 * </ul>
 *
 * <p>为什么不可用时不干脆关掉 FTS 路：关掉就退化成纯向量单路检索，而纯向量在精确编号/数字
 * （如 "KPI-2026"）上是硬伤，双路混合的意义就没了。{@code simple} 虽然粗，但双路结构还在，
 * 精确词仍能被字面命中——降级要降在"分词精度"上，不能降在"少一路"上。
 *
 * <p>探测时机用 {@link ApplicationRunner}（应用启动完成后执行）：此时数据源已就绪，
 * 且早于任何用户检索请求，K4 的 {@code to_tsvector(:tsConfig, ...)} 取到的一定是探测后的值。
 * 探测失败（连不上 PG 等）按"不可用"处理，只降级不阻断启动（§7.3 规则 10：异常必须记录，不吞）。
 */
@Slf4j
@Component
public class ZhparserProbe implements ApplicationRunner {

    /**
     * 用 COUNT 而不是 {@code SELECT 1}：{@code queryForObject} 在零行时会抛
     * {@code EmptyResultDataAccessException}，COUNT 永远返回一行，判定更干净。
     * 常量 SQL 无外部输入拼接，不存在注入风险（§7.11 规则 36）。
     */
    private static final String SQL_CHECK_ZHPARSER =
            "SELECT COUNT(1) FROM pg_extension WHERE extname = 'zhparser'";

    private final JdbcTemplate pgJdbcTemplate;

    private final RagProperties ragProperties;

    /** 最近一次探测结果：zhparser 是否可用（供体检页/诊断接口读取）。 */
    private volatile boolean zhparserAvailable;

    /**
     * 构造探针。
     *
     * @param pgJdbcTemplate 向量库（PostgreSQL）的 JdbcTemplate，见 {@code common/config/PgDataSourceConfig}
     * @param ragProperties  RAG 全局参数，探测结果会写回其 {@code fts.tsConfig}
     */
    public ZhparserProbe(@Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                         RagProperties ragProperties) {
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.ragProperties = ragProperties;
    }

    /**
     * 应用启动完成后自动探测一次。
     *
     * @param args 启动参数，未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        probe();
    }

    /**
     * 执行探测并按结果写回 {@code rag.fts.ts-config}。
     *
     * @return true 表示 zhparser 可用
     */
    public boolean probe() {
        boolean available = false;
        try {
            Integer count = pgJdbcTemplate.queryForObject(SQL_CHECK_ZHPARSER, Integer.class);
            available = count != null && count > 0;
        } catch (DataAccessException e) {
            log.warn("zhparser 探测失败，按不可用处理并降级 simple: {}", e.getMessage());
        }
        this.zhparserAvailable = available;
        applyTsConfig(available);
        return available;
    }

    /**
     * 最近一次探测结论。
     *
     * @return true 表示 zhparser 可用
     */
    public boolean isZhparserAvailable() {
        return zhparserAvailable;
    }

    /** 把探测结论落到 {@code rag.fts.tsConfig}；无论可用与否都不动 {@code fts.enabled}（那是人工总开关）。 */
    private void applyTsConfig(boolean available) {
        RagProperties.Fts fts = ragProperties.getFts();
        if (fts == null) {
            fts = new RagProperties.Fts();
            ragProperties.setFts(fts);
        }
        if (available) {
            fts.setTsConfig(RagProperties.TS_CONFIG_ZHPARSER);
            log.info("zhparser available=true, fts tsConfig={} enabled={}",
                    RagProperties.TS_CONFIG_ZHPARSER, fts.isEnabled());
            return;
        }
        fts.setTsConfig(RagProperties.TS_CONFIG_SIMPLE);
        log.warn("zhparser available=false, FTS 分词降级为 tsConfig={}（中文按字切，召回略差；FTS 路仍开启 enabled={}）",
                RagProperties.TS_CONFIG_SIMPLE, fts.isEnabled());
    }
}
