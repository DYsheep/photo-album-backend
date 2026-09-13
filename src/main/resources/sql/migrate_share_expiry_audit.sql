-- ============================================
-- 迁移脚本：分享链接有效期与私密联动 + 操作审计
-- 【手工追平脚本 · 一次性】用于既有数据库追平结构差异（Flyway 接入前的历史脚本）。
-- 后续结构变更请新增 src/main/resources/db/migration/Vn__*.sql，由 Flyway 自动执行。
-- 适用：已在运行的 photo_album 库（一次性执行）
-- 说明：本脚本可与旧版本代码并存执行，写完字段后旧代码仍可正常运行。
-- ============================================

USE photo_album;

-- --------------------------------------------
-- 1. 分享链接：有效期字段
--    NULL 表示永久有效；到期后接口按"链接不存在或已失效"处理
-- --------------------------------------------
ALTER TABLE t_share_link
    ADD COLUMN expires_at DATETIME DEFAULT NULL COMMENT '到期时间（NULL=永久有效）' AFTER photo_id;

-- 既有分享链接保持"永久有效"（expires_at 为 NULL），行为与迁移前一致。
-- 如需为存量链接统一设置到期时间，可执行（示例：30 天后到期）：
-- UPDATE t_share_link SET expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY) WHERE expires_at IS NULL;

-- --------------------------------------------
-- 2. 操作审计日志表（只增不改）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS t_audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    actor_id    BIGINT       DEFAULT NULL      COMMENT '操作人用户ID（系统动作为 NULL）',
    actor_name  VARCHAR(50)  DEFAULT ''        COMMENT '操作人用户名（冗余保存，便于追溯）',
    action      VARCHAR(50)  NOT NULL          COMMENT '动作: GRANT_ADD/GRANT_REMOVE/USER_CREATE/USER_UPDATE/USER_DELETE',
    target_type VARCHAR(30)  DEFAULT ''        COMMENT '对象类型: permission/user',
    target_id   BIGINT       DEFAULT NULL      COMMENT '对象ID',
    detail      VARCHAR(500) DEFAULT ''        COMMENT '操作详情（不含敏感信息）',
    ip          VARCHAR(45)  DEFAULT NULL      COMMENT '来源IP',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_created (created_at),
    INDEX idx_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- --------------------------------------------
-- 3. 核对
-- --------------------------------------------
-- 3.1 分享链接时效概览（永久 / 已设置到期）
SELECT CASE WHEN expires_at IS NULL THEN '永久有效' ELSE '限时' END AS 有效期类型,
       COUNT(*) AS 数量
FROM t_share_link
GROUP BY CASE WHEN expires_at IS NULL THEN '永久有效' ELSE '限时' END;

-- 3.2 已过期但仍存在的分享链接（接口已不可访问，可手工清理）
SELECT id, code, photo_id, expires_at
FROM t_share_link
WHERE expires_at IS NOT NULL AND expires_at <= NOW()
ORDER BY expires_at;

-- 3.3 私密照片仍存在分享链接的情况（这些链接对无权限访问者已失效，仅作提示）
SELECT s.id AS 分享ID, s.code AS 分享码, p.id AS 照片ID, p.title AS 标题, s.expires_at AS 到期时间
FROM t_share_link s
JOIN t_photo p ON p.id = s.photo_id
WHERE p.is_private = 1
ORDER BY s.id;
