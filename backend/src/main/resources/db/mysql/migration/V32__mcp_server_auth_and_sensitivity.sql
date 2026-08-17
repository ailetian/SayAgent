-- M10/T1 + T4（§9 Flyway，禁止直连 ALTER 手写执行）：给 mcp_server 追加鉴权与数据敏感度列。
-- 版本号顺延自 V17(建表) / V31(M9)，V32 > 既有最大版本。
-- 索引纪律（§6.2）：auth_type 选择性低，不加独立索引；auth_config 为敏感 TEXT，不建索引。
-- 软删不受影响：仅追加列，既有 @SQLRestriction("deleted=0") 不变（§6.1）。

ALTER TABLE `mcp_server`
  ADD COLUMN `auth_type`         VARCHAR(20)  NOT NULL DEFAULT 'NONE'
      COMMENT '鉴权类型：NONE/BEARER/APIKEY/HEADER（M10/T1）',
  ADD COLUMN `auth_config`       TEXT          NULL
      COMMENT '鉴权凭据 JSON（如 {"token":"..."} / {"key":"..."} / {"headers":{...}}），敏感，禁止返前端/打日志（§7.11）',
  ADD COLUMN `data_sensitivity`  VARCHAR(20)  NOT NULL DEFAULT 'INTERNAL'
      COMMENT '数据敏感度：PUBLIC/INTERNAL/CONFIDENTIAL/FINANCE_HR（M10/T4）';
