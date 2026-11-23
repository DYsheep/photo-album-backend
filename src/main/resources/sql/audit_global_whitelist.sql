-- ============================================
-- 只读核查脚本：viewer 的"全部私密内容"白名单
--
-- 背景（P1-8）：
--   src/main/resources/sql/migrate_permission_policy.sql 第 36-43 行为兼容旧行为，
--   给"角色为 viewer 且原本没有任何白名单条目"的账号批量写入了一条
--   (W, global) 授权，等价于"可见全站全部私密照片"。
--   这是有意的迁移动作，但效果是：执行过该脚本的库，所有 viewer 立刻可见全部私密内容。
--
-- 用法（只读，不修改任何数据）：
--   mysql -u<user> -p photo_album < audit_global_whitelist.sql
--
-- 处理建议：
--   1. 先跑本脚本列出当前持 global 白名单的账号，逐个确认是否确需"全部私密"；
--   2. 需要收窄的，改为按 collection / category / tag / photo 逐项授权，
--      再删除其 global 条目（见文件末尾的可选清理语句，默认注释掉）；
--   3. 保留的账号应有书面授权记录（此脚本输出的 created_at / created_by 即为线索）。
-- ============================================

USE photo_album;

-- 1. 当前持"全部私密内容"白名单的账号清单
SELECT u.id                         AS 用户ID,
       u.username                   AS 账号,
       u.role                       AS 角色,
       u.can_view_private           AS 私密能力位,
       t.created_at                 AS 授权时间,
       t.created_by                 AS 授权操作人,
       CASE
           WHEN u.role = 'viewer' THEN '存量 viewer，可能来自迁移脚本'
           ELSE '非 viewer，需人工确认来源'
           END                      AS 来源判断
FROM t_auth_tuple t
         JOIN t_user u ON u.id = t.subject_id
WHERE t.subject_type = 'user'
  AND t.relation = 'allow'
  AND t.object_type = 'global'
ORDER BY t.created_at, u.id;

-- 2. 按角色汇总：一眼看出有多少账号拿到全量私密可见权
SELECT u.role                              AS 角色,
       COUNT(DISTINCT u.id)                AS 账号数,
       COUNT(*)                            AS global白名单条目数
FROM t_auth_tuple t
         JOIN t_user u ON u.id = t.subject_id
WHERE t.subject_type = 'user'
  AND t.relation = 'allow'
  AND t.object_type = 'global'
GROUP BY u.role;

-- 3. 既无任何白名单、又具私密能力位的账号（默认拒绝，属于正常状态，供对照）
SELECT u.id AS 用户ID, u.username AS 账号, u.role AS 角色
FROM t_user u
WHERE u.can_view_private = 1
  AND NOT EXISTS (SELECT 1
                  FROM t_auth_tuple t
                  WHERE t.subject_type = 'user'
                    AND t.subject_id = u.id
                    AND t.relation = 'allow');

-- ============================================
-- 可选：收窄某个账号的全量私密可见权（确认后再手工执行，默认注释）
--
-- UPDATE 说明：只删 (allow, global) 这一条，该账号其余按对象授权的条目保持不变，
-- 删除后它的可见范围即收缩为"仅被显式授权的对象"。
-- ============================================
-- DELETE FROM t_auth_tuple
-- WHERE subject_type = 'user'
--   AND subject_id = <用户ID>
--   AND relation = 'allow'
--   AND object_type = 'global';
