-- ============================================
-- V3：合集分享（把"照片分享"泛化为"照片/合集分享"）
--   · target_type / target_id：分享对象（photo / collection）
--   · photo_id 改为可空（合集分享不填），老数据迁移为 target_type='photo' + target_id=photo_id
--   · include_private：合集分享是否包含私密照片（默认 0=仅公开）
--   · access_code：访问口令（BCrypt 哈希；NULL 表示无需口令）
-- ============================================

ALTER TABLE t_share_link
    MODIFY COLUMN photo_id BIGINT NULL COMMENT '照片ID（照片分享使用；合集分享为 NULL）',
    ADD COLUMN target_type VARCHAR(20) NOT NULL DEFAULT 'photo' COMMENT '分享对象类型: photo/collection',
    ADD COLUMN target_id BIGINT NULL COMMENT '对象ID（照片或合集）',
    ADD COLUMN include_private TINYINT NOT NULL DEFAULT 0 COMMENT '合集分享是否包含私密照片',
    ADD COLUMN access_code VARCHAR(100) DEFAULT NULL COMMENT '访问口令（BCrypt，NULL=无口令）';

-- 老数据迁移：全部视为照片分享，语义不变
UPDATE t_share_link SET target_id = photo_id WHERE target_id IS NULL;

CREATE INDEX idx_share_target ON t_share_link (target_type, target_id);

-- 核对
SELECT id, code, target_type, target_id, photo_id, include_private, expires_at FROM t_share_link ORDER BY id;
