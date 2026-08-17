-- M9/T1 用户管理与角色权限「地基迁移」
-- 依据 plans/M9/T1_数据迁移与角色枚举.md + 10_M9_审查报告 修订项
--
-- 落地内容：
--   1) 三档角色 UserRole: ADMIN/OPERATOR/USER（枚举改在 UserRole.java，本文件只负责 user 表补列）
--   2) menu_item / role_menu 两表 + 菜单种子（含 /skills，修复审查 P1-4）
--   3) resource_access 统一资源授权表（角色基线 + 个人覆盖，混合）
--   4) knowledge_base / agent 加 visibility 列，默认 RESTRICTED（secure by default，§2.1）
--   5) user 表补 display_name(64) / email(128)（支撑 POST /api/users 契约）
--   6) 存量 KB / Agent 按 creator 回填创建者本人全权授权（修复审查 P1-3）
--   7) 无创建者的脏数据置 PUBLIC（防历史丢失）
--
-- 规范与审查约束（务必遵守）：
--   * §9 Flyway 版本纪律：当前已 applied 至 V30，本迁移用 V31；严禁用 V28（已存在会校验失败，P0-1）
--   * §6 / §9 数据模型走迁移：所有 DDL 在此一处，禁止手动 ALTER
--   * P2-7：visibility / principal_type / resource_type 用 VARCHAR(20)（非 ENUM，兑现「新增资源类型不另建表」）
--   * P0-2：resource_access.principal_id（USER 类）= 登录名 username(VARCHAR(64))，与 creator_id/created_by 同源，不可存数字 id
--   * P2-8：resource_access 不继承 BaseEntity → 无 deleted/updated_at，仅 created_at（避免 @SQLRestriction 找不到 deleted 列）
--   * §6.1/§6.2：每表含 idx_created_at；外键/高频过滤列建索引；索引命名 idx_*

-- ========== 1. 菜单定义表（种子数据） ==========
CREATE TABLE `menu_item` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`       VARCHAR(50)   NOT NULL                  COMMENT '菜单编码(唯一，前端路由 meta 映射)',
  `name`       VARCHAR(64)   NOT NULL                  COMMENT '菜单显示名',
  `path`       VARCHAR(100)  NOT NULL                  COMMENT '前端路由路径',
  `icon`       VARCHAR(50)   NULL                      COMMENT '图标名',
  `sort`       INT           NOT NULL DEFAULT 0        COMMENT '排序权重，越小越靠前',
  `created_at` DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`    TINYINT(1)    NOT NULL DEFAULT 0        COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`, `deleted`),
  KEY `idx_sort` (`sort`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='侧边栏菜单定义(种子数据)';

-- ========== 2. 角色-菜单映射表（种子数据） ==========
CREATE TABLE `role_menu` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_code`  VARCHAR(20)    NOT NULL                  COMMENT '角色 ADMIN/OPERATOR/USER',
  `menu_code`  VARCHAR(50)    NOT NULL                  COMMENT 'menu_item.code',
  `created_at` DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`    TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_code`, `menu_code`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单映射(种子数据)';

-- ========== 3. 统一资源授权表（角色基线 + 个人覆盖，混合） ==========
-- 注意：不继承 BaseEntity（P2-8），无 deleted/updated_at，仅 created_at。
-- principal_id(USER) 存登录名 username，与 knowledge_base.creator_id / agent.created_by 同源（P0-2）。
CREATE TABLE `resource_access` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `principal_type` VARCHAR(20)    NOT NULL                  COMMENT '授权主体类型 ROLE/USER',
  `principal_id`   VARCHAR(64)    NOT NULL                  COMMENT '授权主体：ROLE=角色名，USER=登录名(username)',
  `resource_type`  VARCHAR(20)    NOT NULL                  COMMENT '资源类型 KB/AGENT(可扩展，不用 ENUM，P2-7)',
  `resource_id`    BIGINT UNSIGNED NOT NULL                  COMMENT '资源 id(knowledge_base.id / agent.id)',
  `can_read`       TINYINT(1)     NOT NULL DEFAULT 1        COMMENT '可读',
  `can_write`      TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '可写',
  `can_use`        TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '可用(对话/调用)',
  `can_edit`       TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '可编辑配置',
  `created_at`     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_principal_resource` (`principal_type`, `principal_id`, `resource_type`, `resource_id`),
  KEY `idx_resource` (`resource_type`, `resource_id`),
  KEY `idx_principal` (`principal_type`, `principal_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一资源授权(角色基线+个人覆盖，混合)';

-- ========== 4. knowledge_base 加 visibility（默认 RESTRICTED，§2.1） ==========
ALTER TABLE `knowledge_base`
    ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT 'RESTRICTED'
        COMMENT '可见性 PUBLIC/RESTRICTED(默认 secure by default §2.1)',
    ADD KEY `idx_visibility` (`visibility`);

-- ========== 5. agent 加 visibility（默认 RESTRICTED，§2.1） ==========
ALTER TABLE `agent`
    ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT 'RESTRICTED'
        COMMENT '可见性 PUBLIC/RESTRICTED(默认 secure by default §2.1)',
    ADD KEY `idx_visibility` (`visibility`);

-- ========== 6. user 表补 display_name / email（支撑 POST /api/users 契约，§2.1） ==========
ALTER TABLE `user`
    ADD COLUMN `display_name` VARCHAR(64) NULL COMMENT '显示名',
    ADD COLUMN `email`       VARCHAR(128) NULL COMMENT '邮箱';

-- ========== 7. 菜单种子（7 项，含 /skills，修复审查 P1-4） ==========
INSERT INTO `menu_item` (`code`, `name`, `path`, `icon`, `sort`) VALUES
  ('chat',      '对话',     '/chat',      'chat',       10),
  ('agents',    '智能体',   '/agents',    'bot',        20),
  ('knowledge', '知识库',   '/knowledge', 'library',    30),
  ('models',    '模型管理', '/models',    'cpu',        40),
  ('mcp',       'MCP',      '/mcp',       'connection', 50),
  ('users',     '用户管理', '/users',     'user',       60),
  ('skills',    '技能库',   '/skills',    'spark',      70);

-- ========== 8. 角色-菜单种子（ADMIN 7 / OPERATOR 4 / USER 3） ==========
INSERT INTO `role_menu` (`role_code`, `menu_code`) VALUES
  -- ADMIN：全部 7 项
  ('ADMIN','chat'),('ADMIN','agents'),('ADMIN','knowledge'),('ADMIN','models'),('ADMIN','mcp'),('ADMIN','users'),('ADMIN','skills'),
  -- OPERATOR：对话 / 智能体 / 知识库 / 技能库（4 项）
  ('OPERATOR','chat'),('OPERATOR','agents'),('OPERATOR','knowledge'),('OPERATOR','skills'),
  -- USER：对话 / 智能体 / 知识库（3 项）
  ('USER','chat'),('USER','agents'),('USER','knowledge');

-- ========== 9. 存量回填：给历史 KB / Agent 的创建者本人补全权授权（修复审查 P1-3） ==========
INSERT INTO `resource_access` (`principal_type`, `principal_id`, `resource_type`, `resource_id`, `can_read`, `can_write`, `can_use`, `can_edit`, `created_at`)
SELECT 'USER', kb.creator_id, 'KB', kb.id, 1, 1, 1, 1, NOW(3)
FROM `knowledge_base` kb
WHERE kb.creator_id IS NOT NULL AND kb.creator_id <> '' AND kb.deleted = 0;

INSERT INTO `resource_access` (`principal_type`, `principal_id`, `resource_type`, `resource_id`, `can_read`, `can_write`, `can_use`, `can_edit`, `created_at`)
SELECT 'USER', a.created_by, 'AGENT', a.id, 1, 1, 1, 1, NOW(3)
FROM `agent` a
WHERE a.created_by IS NOT NULL AND a.created_by <> '' AND a.deleted = 0;

-- ========== 10. 无创建者的脏数据置 PUBLIC（防历史丢失，P1-3） ==========
UPDATE `knowledge_base` SET `visibility` = 'PUBLIC' WHERE (`creator_id` IS NULL OR `creator_id` = '') AND `deleted` = 0;
UPDATE `agent`          SET `visibility` = 'PUBLIC' WHERE (`created_by` IS NULL OR `created_by` = '') AND `deleted` = 0;
