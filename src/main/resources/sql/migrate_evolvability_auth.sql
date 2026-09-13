-- ============================================
-- 迁移脚本：能力位与令牌吊销（可演进性 + 认证侧遗留）
-- 【手工追平脚本 · 一次性】用于既有数据库追平结构差异（Flyway 接入前的历史脚本）。
-- 后续结构变更请新增 src/main/resources/db/migration/Vn__*.sql，由 Flyway 自动执行。
-- 适用：已在运行的 photo_album 库（一次性执行）
-- 说明：可先于代码发布执行；执行后旧版本代码行为不变（旧代码不读这两个字段）。
-- ============================================

USE photo_album;

-- --------------------------------------------
-- 1. 用户表：能力位（私密查看）与令牌版本
--    · can_view_private：把"能否查看私密内容"从角色中剥离为独立能力位，
--      角色（role）退回为纯标识，后续新增角色类型无需改动鉴权代码；
--    · token_version：令牌版本，改密与登出时自增，使已签发的令牌立即失效。
-- --------------------------------------------
ALTER TABLE t_user
    ADD COLUMN can_view_private TINYINT DEFAULT 0 COMMENT '私密查看能力: 0=无 1=有（仍需逐项授权）' AFTER can_manage,
    ADD COLUMN token_version    INT     DEFAULT 0 COMMENT '令牌版本（改密/登出时自增以吊销旧令牌）' AFTER can_view_private;

-- --------------------------------------------
-- 2. 数据迁移：保持既有 viewer 的可见能力不变
--    旧模型下"能否看私密"由角色 viewer 决定，新模型由能力位决定，
--    因此把现有 viewer 账号的能力位补齐为 1（其可见范围仍由 t_user_permission 决定，不变）。
-- --------------------------------------------
UPDATE t_user SET can_view_private = 1 WHERE role = 'viewer' AND (can_view_private IS NULL OR can_view_private = 0);

-- 注意：角色取值大小写/拼写异常的账号（如 Viewer）不在此补齐，请按需在后台手动勾选"可查看私密内容"。

-- --------------------------------------------
-- 3. 核对
-- --------------------------------------------
-- 3.1 各账号的能力位现状
SELECT id, username, role, can_upload, can_manage, can_view_private, token_version
FROM t_user
ORDER BY id;

-- 3.2 仍依赖 viewer 角色但未获得能力位的账号（预期为空；若有则按需勾选）
SELECT id, username, role FROM t_user
WHERE role = 'viewer' AND (can_view_private IS NULL OR can_view_private = 0);

-- 3.3 授权条目按维度分布（含新增的 category 维度）
SELECT target_type AS 授权维度, perm_type AS 名单类型, COUNT(*) AS 条目数
FROM t_user_permission
GROUP BY target_type, perm_type
ORDER BY target_type, perm_type;
