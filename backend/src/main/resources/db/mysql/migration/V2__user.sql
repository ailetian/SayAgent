-- V2：建立用户表（§6.1 四字段 + §6.2 索引纪律）
CREATE TABLE `user` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`   VARCHAR(64) NOT NULL COMMENT '登录名',
  `password`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
  `role`       VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`, `deleted`)   -- §6.2 联合索引必须含 deleted
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户';
