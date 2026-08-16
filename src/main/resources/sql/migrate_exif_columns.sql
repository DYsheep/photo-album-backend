-- ============================================
-- EXIF 结构化字段增量迁移
-- 用途: 为已有数据库添加 EXIF 结构化字段列
-- 注意: MySQL 不支持 ADD COLUMN IF NOT EXISTS，
--       如果列已存在会报错（Duplicate column），可忽略
-- ============================================

ALTER TABLE t_photo ADD COLUMN camera_model  VARCHAR(100) DEFAULT '' COMMENT '相机型号';
ALTER TABLE t_photo ADD COLUMN aperture      VARCHAR(20)  DEFAULT '' COMMENT '光圈值，如 f/2.8';
ALTER TABLE t_photo ADD COLUMN shutter_speed VARCHAR(20)  DEFAULT '' COMMENT '快门速度，如 1/125s';
ALTER TABLE t_photo ADD COLUMN iso           VARCHAR(10)  DEFAULT '' COMMENT 'ISO感光度';
ALTER TABLE t_photo ADD COLUMN focal_length  VARCHAR(20)  DEFAULT '' COMMENT '焦距，如 50mm';
ALTER TABLE t_photo ADD COLUMN date_taken    VARCHAR(30)  DEFAULT '' COMMENT '拍摄时间（EXIF原始值）';
