package com.sayagent.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产秘钥强化闸门（P3 收口）。
 *
 * 根因：此前 JWT 秘钥、管理员密码、数据库密码均为开发占位弱默认值（dev-only / admin123 / hify / sayagent / root），
 * 仅靠注释提醒「生产须覆盖」，生产环境不会真正拦住 —— 克隆者一旦直接上线即用弱口令。
 *
 * 做法：
 *  - 当 SAYAGENT_PRODUCTION=true（生产）时，若存在弱默认秘钥/弱密码，启动直接失败（fail-fast），
 *    并打印可操作的整改提示，杜绝「弱口令上线」。
 *  - 演示/开发模式（SAYAGENT_PRODUCTION=false，默认）只打印 WARN 提醒，不阻断启动，保证克隆即跑。
 *
 * 与「模型供应商由客户自配」的边界无关：这里只管「系统自身的秘钥强度」，不触碰任何 LLM/Embedding key。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHardeningValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityHardeningValidator.class);

    private static final String DEV_JWT_PLACEHOLDER = "dev-only-secret-key-please-change-in-prod-32bytes!!";
    private static final List<String> WEAK_SECRETS = List.of(
            "hify", "sayagent", "root", "admin123", "admin", "password", "123456",
            "dev-only-secret-key-please-change-in-prod-32bytes!!");

    @Value("${SAYAGENT_PRODUCTION:false}")
    private boolean production;

    @Value("${sayagent.jwt.secret}")
    private String jwtSecret;

    @Value("${sayagent.admin.password}")
    private String adminPassword;

    @Value("${MYSQL_PASSWORD:sayagent}")
    private String mysqlPassword;

    @Value("${MYSQL_ROOT_PASSWORD:root}")
    private String mysqlRootPassword;

    @Value("${POSTGRES_PASSWORD:sayagent}")
    private String postgresPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (!production) {
            log.warn("【安全提醒】当前为演示/开发模式（SAYAGENT_PRODUCTION=false），使用了开发弱默认秘钥/密码。"
                    + "任何对外部署务必："
                    + "  1) 在 deploy/.env 设置 SAYAGENT_PRODUCTION=true；"
                    + "  2) 将 SAYAGENT_JWT_SECRET 改为至少 32 字节随机串（如 `openssl rand -base64 48`）；"
                    + "  3) 将 SAYAGENT_ADMIN_PASSWORD / MYSQL_PASSWORD / POSTGRES_PASSWORD 改为强密码。");
            return;
        }

        List<String> violations = new ArrayList<>();

        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.equals(DEV_JWT_PLACEHOLDER) || jwtSecret.length() < 32) {
            violations.add("SAYAGENT_JWT_SECRET 缺失 / 仍是开发占位符 / 长度不足 32 字节");
        }
        checkWeak("管理员密码 SAYAGENT_ADMIN_PASSWORD", adminPassword, violations);
        checkWeak("MySQL 密码 MYSQL_PASSWORD", mysqlPassword, violations);
        checkWeak("MySQL root 密码 MYSQL_ROOT_PASSWORD", mysqlRootPassword, violations);
        checkWeak("PostgreSQL 密码 POSTGRES_PASSWORD", postgresPassword, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境秘钥校验未通过，已拒绝启动（fail-fast）：\n  - "
                            + String.join("\n  - ", violations)
                            + "\n请修改 deploy/.env 中的弱秘钥/密码后重试；"
                            + "若仅本地演示，将 SAYAGENT_PRODUCTION 设为 false。");
        }
        log.info("生产秘钥校验通过：未检测到弱默认秘钥/密码。");
    }

    private void checkWeak(String label, String value, List<String> violations) {
        if (value == null || value.isBlank() || WEAK_SECRETS.contains(value)) {
            violations.add(label + " 仍为弱默认值（" + value + "），请改为强密码");
        }
    }
}
