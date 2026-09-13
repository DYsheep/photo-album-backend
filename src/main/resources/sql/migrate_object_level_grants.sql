-- ============================================
-- 迁移脚本：对象级管理权（合集协作者）
-- 【手工追平脚本 · 一次性】用于既有数据库追平结构差异（Flyway 接入前的历史脚本）。
-- 后续结构变更请新增 src/main/resources/db/migration/Vn__*.sql，由 Flyway 自动执行。
-- 适用：已在运行的 photo_album 库
-- ============================================

USE photo_album;

-- --------------------------------------------
-- 1. 合集协作者表
--    被指派的账号可维护对应的合集（改名、封面、增删合集内照片），
--    但不具备全站管理权限，也看不到其他合集。
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS t_collection_member (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    collection_id BIGINT      NOT NULL              COMMENT '合集ID',
    user_id       BIGINT      NOT NULL              COMMENT '成员用户ID',
    member_role   VARCHAR(20) DEFAULT 'editor'      COMMENT '成员角色: editor=可维护该合集',
    created_by    BIGINT      DEFAULT NULL          COMMENT '指派操作人用户ID',
    created_at    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_collection_user (collection_id, user_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合集协作者表';

-- --------------------------------------------
-- 2. 核对
-- --------------------------------------------
-- 2.1 现有协作者指派情况（迁移后应为空，由管理员在后台指派）
SELECT m.id, m.collection_id, c.name AS 合集名, m.user_id, u.username AS 账号,
       m.member_role, m.created_at
FROM t_collection_member m
LEFT JOIN t_collection c ON c.id = m.collection_id
LEFT JOIN t_user u ON u.id = m.user_id
ORDER BY m.id;

-- 2.2 提示：协作者只能进入"合集管理"页并看到被指派的合集；
--     若同时需要其上传照片或编辑照片本身，请另行勾选对应的能力位（上传 / 管理）。
