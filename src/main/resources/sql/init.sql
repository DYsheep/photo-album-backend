-- ============================================
-- 摄影相册数据库初始化脚本
-- 数据库: photo_album
-- ============================================

CREATE DATABASE IF NOT EXISTS photo_album DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE photo_album;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(50)  NOT NULL UNIQUE  COMMENT '用户名',
    password    VARCHAR(100) NOT NULL         COMMENT 'BCrypt加密密码',
    nickname    VARCHAR(50)  DEFAULT ''       COMMENT '昵称',
    avatar      VARCHAR(255) DEFAULT ''       COMMENT '头像URL',
    role        VARCHAR(20)  DEFAULT 'user'   COMMENT '角色: admin/user',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 分类表
CREATE TABLE IF NOT EXISTS t_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)  NOT NULL          COMMENT '分类名称',
    description VARCHAR(200) DEFAULT ''        COMMENT '描述',
    sort_order  INT          DEFAULT 0         COMMENT '排序权重',
    photo_count INT          DEFAULT 0         COMMENT '照片数量',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类表';

-- 照片表
CREATE TABLE IF NOT EXISTS t_photo (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title         VARCHAR(100) NOT NULL          COMMENT '标题',
    description   TEXT         DEFAULT NULL       COMMENT '描述',
    category_id   BIGINT       DEFAULT NULL       COMMENT '关联分类ID',
    url           VARCHAR(500) NOT NULL           COMMENT '图片存储路径',
    file_name     VARCHAR(200) DEFAULT ''         COMMENT '原始文件名',
    file_size     BIGINT       DEFAULT 0          COMMENT '文件大小(bytes)',
    tags          VARCHAR(200) DEFAULT ''         COMMENT '标签(逗号分隔)',
    view_count    INT          DEFAULT 0          COMMENT '浏览量',
    exif_info     TEXT         DEFAULT NULL       COMMENT 'EXIF信息(JSON完整数据)',
    camera_model  VARCHAR(100) DEFAULT ''         COMMENT '相机型号',
    aperture      VARCHAR(20)  DEFAULT ''         COMMENT '光圈值，如 f/2.8',
    shutter_speed VARCHAR(20)  DEFAULT ''         COMMENT '快门速度，如 1/125s',
    iso           VARCHAR(10)  DEFAULT ''         COMMENT 'ISO感光度',
    focal_length  VARCHAR(20)  DEFAULT ''         COMMENT '焦距，如 50mm',
    date_taken    VARCHAR(30)  DEFAULT ''         COMMENT '拍摄时间（EXIF原始值）',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_category (category_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照片表';

-- ============================================
-- 初始化数据
-- ============================================

-- 默认管理员账号: admin / 123456
-- BCrypt hash of "123456": $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi
INSERT INTO t_user (username, password, nickname, role) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员', 'admin');

-- 默认分类
INSERT INTO t_category (name, description, sort_order) VALUES
('风景', '自然风光与城市景色', 1),
('人像', '人物肖像摄影', 2),
('街拍', '街头纪实与人文', 3),
('建筑', '建筑空间美学', 4),
('自然', '动植物与自然生态', 5);
