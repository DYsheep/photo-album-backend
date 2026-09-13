-- ============================================
-- 权限模型迁移脚本（默认拒绝 + 审计字段 + 唯一约束）
-- 【手工追平脚本 · 一次性】用于既有数据库追平结构差异（Flyway 接入前的历史脚本）。
-- 后续结构变更请新增 src/main/resources/db/migration/Vn__*.sql，由 Flyway 自动执行。
-- 适用：已在运行的 photo_album 库（一次性执行）
-- 说明：本脚本可先于代码发布执行，与旧版本代码兼容。
-- ============================================

USE photo_album;

-- --------------------------------------------
-- 1. 表结构：审计字段与唯一约束
-- --------------------------------------------
ALTER TABLE t_user_permission
    MODIFY COLUMN perm_type   CHAR(1)     NOT NULL COMMENT 'W=白名单(定义可见范围) B=黑名单(在范围内排除)',
    MODIFY COLUMN target_type VARCHAR(20) NOT NULL COMMENT '授权对象类型: photo/collection/global',
    MODIFY COLUMN target_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '授权对象ID（global 固定为 0）',
    ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    ADD COLUMN created_by BIGINT   DEFAULT NULL COMMENT '授权操作人用户ID（审计）';

-- 唯一约束：从数据库层杜绝重复授权条目（重复执行会提示索引已存在，可忽略）
ALTER TABLE t_user_permission
    ADD UNIQUE KEY uk_user_target (user_id, perm_type, target_type, target_id);

-- 原 idx_user 已被 uk_user_target 的前缀覆盖，可安全删除（可选）
-- ALTER TABLE t_user_permission DROP INDEX idx_user;

-- --------------------------------------------
-- 2. 数据迁移：保持既有 viewer 的实际可见范围不变
--    旧行为：viewer 无任何条目 = 可看全部私密；仅有黑名单 = 可看除黑名单外全部
--    新行为：无白名单条目 = 看不到任何私密
--    因此为"严格小写 role='viewer'"且没有白名单条目的账号补一条 global 白名单。
--    注意：role 存在大小写/拼写错误（如 Viewer）的账号不在此补授权，迁移后将与旧版
--    实际表现一致（看不到任何私密内容），如需放行请在后台逐项授权。
-- --------------------------------------------
INSERT INTO t_user_permission (user_id, perm_type, target_type, target_id, created_at, created_by)
SELECT u.id, 'W', 'global', 0, NOW(), NULL
FROM t_user u
WHERE u.role = 'viewer'
  AND NOT EXISTS (
      SELECT 1 FROM t_user_permission p
      WHERE p.user_id = u.id AND p.perm_type = 'W'
  );

-- --------------------------------------------
-- 3. 角色值归一化：消除大小写导致的鉴权口径不一致
--    （鉴权层按大写比较、业务层按小写比较，role='Admin' 会造成"接口放得开、业务不认"）
-- --------------------------------------------
UPDATE t_user SET role = LOWER(TRIM(role)) WHERE role <> LOWER(TRIM(role));

-- --------------------------------------------
-- 4. 迁移结果核对（人工检查下列输出）
-- --------------------------------------------
-- 4.1 各账号当前授权情况
SELECT u.id, u.username, u.role, u.can_upload, u.can_manage,
       SUM(CASE WHEN p.perm_type = 'W' THEN 1 ELSE 0 END) AS 白名单数,
       SUM(CASE WHEN p.perm_type = 'B' THEN 1 ELSE 0 END) AS 黑名单数
FROM t_user u
LEFT JOIN t_user_permission p ON p.user_id = u.id
GROUP BY u.id, u.username, u.role, u.can_upload, u.can_manage
ORDER BY u.id;

-- 4.2 存在角色取值异常的账号（预期为空）
SELECT id, username, role FROM t_user
WHERE role NOT IN ('admin', 'viewer', 'user');

-- 4.3 同时存在白名单与黑名单的账号（新规则下黑名单会在白名单范围内生效，请人工确认重叠条目）
SELECT user_id, COUNT(*) AS 条目数
FROM t_user_permission
GROUP BY user_id
HAVING SUM(CASE WHEN perm_type = 'W' THEN 1 ELSE 0 END) > 0
   AND SUM(CASE WHEN perm_type = 'B' THEN 1 ELSE 0 END) > 0;
