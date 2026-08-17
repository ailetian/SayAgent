-- M9/方案 A：USER 仅保留「对话」菜单
-- 依据 AGENTS.md §2.1：USER 普通成员「仅对话、使用被授权资源，不能自建 KB/Agent」。
-- 故从 USER 的 role_menu 移除 agents / knowledge 两个「搭建类」菜单，仅保留 chat。
-- KB/Agent 由 OPERATOR/ADMIN 在后台搭好，USER 在「对话」页的 Agent 下拉框里选用即可，
-- KB 价值通过「被 Agent 调用」间接兑现，无需 USER 打开配置页。
--
-- 注意：不直接改 V31（已 applied，改之会触发 Flyway 校验和失败），此处用 additive 删除。
-- DELETE 幂等：即使未来重启 9095 让 Flyway 重跑本迁移，删除不存在的行也无副作用。

DELETE FROM `role_menu`
WHERE `role_code` = 'USER'
  AND `menu_code` IN ('agents', 'knowledge');
