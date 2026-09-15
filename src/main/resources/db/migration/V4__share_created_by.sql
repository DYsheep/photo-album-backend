-- ============================================
-- V4：分享链接记录创建人
--   用途：合集分享在"包含私密照片"时，必须以【创建该分享的账号】的可见集为准，
--        并扣除其黑名单（deny）命中的照片，确保被 deny 的内容绝不通过分享链接泄露。
-- ============================================

ALTER TABLE t_share_link
    ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID（合集分享可见性判定依据）';

UPDATE t_share_link SET created_by = NULL WHERE created_by IS NULL;

SELECT id, code, target_type, target_id, created_by FROM t_share_link ORDER BY id;
