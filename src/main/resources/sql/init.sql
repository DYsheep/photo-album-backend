-- ============================================
-- 摄影相册数据库初始化脚本
-- 数据库: photo_album
-- 说明: 本脚本仅包含结构定义与无口令的账号占位记录，
--       不包含任何默认口令或口令哈希（安全要求，见文件末尾说明）。
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
    role        VARCHAR(20)  DEFAULT 'user'   COMMENT '角色: admin/viewer/user',
    can_upload  TINYINT      DEFAULT 0        COMMENT '上传权限: 0=无 1=有',
    can_manage  TINYINT      DEFAULT 0        COMMENT '管理权限: 0=无 1=有',
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
    title         VARCHAR(100) NOT NULL           COMMENT '标题',
    description   TEXT         DEFAULT NULL       COMMENT '描述',
    category_id   BIGINT       DEFAULT NULL       COMMENT '关联分类ID',
    url           VARCHAR(500) NOT NULL           COMMENT '图片存储路径',
    thumbnail_url VARCHAR(500) DEFAULT NULL       COMMENT '缩略图存储路径',
    file_name     VARCHAR(200) DEFAULT ''         COMMENT '原始文件名',
    file_size     BIGINT       DEFAULT 0          COMMENT '文件大小(bytes)',
    tags          VARCHAR(200) DEFAULT ''         COMMENT '标签(逗号分隔)',
    is_private    TINYINT      DEFAULT 0          COMMENT '是否私密: 0=公开 1=私密',
    view_count    INT          DEFAULT 0          COMMENT '浏览量',
    like_count    INT          DEFAULT 0          COMMENT '点赞数',
    exif_info     TEXT         DEFAULT NULL       COMMENT 'EXIF信息(JSON完整数据)',
    camera_model  VARCHAR(100) DEFAULT ''         COMMENT '相机型号',
    aperture      VARCHAR(20)  DEFAULT ''         COMMENT '光圈值，如 f/2.8',
    shutter_speed VARCHAR(20)  DEFAULT ''         COMMENT '快门速度，如 1/125s',
    iso           VARCHAR(10)  DEFAULT ''         COMMENT 'ISO感光度',
    focal_length  VARCHAR(20)  DEFAULT ''         COMMENT '焦距，如 50mm',
    date_taken    VARCHAR(30)  DEFAULT ''         COMMENT '拍摄时间（EXIF原始值）',
    gps_latitude  DOUBLE       DEFAULT NULL       COMMENT 'GPS 纬度',
    gps_longitude DOUBLE       DEFAULT NULL       COMMENT 'GPS 经度',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_category (category_id),
    INDEX idx_created (created_at),
    INDEX idx_private (is_private)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照片表';

-- 合集表
CREATE TABLE IF NOT EXISTS t_collection (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name           VARCHAR(100) NOT NULL           COMMENT '合集名称',
    description    VARCHAR(500) DEFAULT ''         COMMENT '合集描述',
    cover_photo_id BIGINT       DEFAULT NULL       COMMENT '封面照片ID',
    sort_order     INT          DEFAULT 0          COMMENT '排序权重',
    is_published   TINYINT      DEFAULT 0          COMMENT '是否发布: 0=未发布 1=已发布',
    is_private     TINYINT      DEFAULT 0          COMMENT '是否私密: 0=公开 1=私密',
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_published (is_published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合集表';

-- 合集-照片关联表
CREATE TABLE IF NOT EXISTS t_collection_photos (
    id            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    collection_id BIGINT NOT NULL              COMMENT '合集ID',
    photo_id      BIGINT NOT NULL              COMMENT '照片ID',
    sort_order    INT    DEFAULT 0             COMMENT '合集内排序',
    PRIMARY KEY (id),
    INDEX idx_collection (collection_id),
    INDEX idx_photo (photo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合集照片关联表';

-- 分享链接表
CREATE TABLE IF NOT EXISTS t_share_link (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(32) NOT NULL UNIQUE         COMMENT '分享码',
    photo_id   BIGINT      NOT NULL                COMMENT '照片ID',
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_photo (photo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接表';

-- 用户权限表（照片/合集级白名单与黑名单）
-- 默认拒绝模型：白名单（W）定义可见范围（global=全部私密），黑名单（B）在范围内排除；
-- 无任何白名单条目时看不到任何私密内容。
CREATE TABLE IF NOT EXISTS t_user_permission (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT      NOT NULL                COMMENT '用户ID',
    perm_type   CHAR(1)     NOT NULL                COMMENT 'W=白名单(定义可见范围) B=黑名单(在范围内排除)',
    target_type VARCHAR(20) NOT NULL                COMMENT '授权对象类型: photo/collection/global',
    target_id   BIGINT      NOT NULL DEFAULT 0      COMMENT '授权对象ID（global 固定为 0）',
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    created_by  BIGINT      DEFAULT NULL            COMMENT '授权操作人用户ID（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_target (user_id, perm_type, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户权限表';

-- ============================================
-- 初始化数据
-- ============================================

-- 管理员账号占位记录（不含任何可用口令）
-- 安全要求：仓库中不得保留默认口令或示例口令哈希。
-- 本行的 password 为占位符，不是合法的 BCrypt 哈希，任何口令都无法通过校验，
-- 因此在完成下面第 2 步之前，后台处于不可登录状态。
-- 首次部署步骤：
--   1) 生成 BCrypt 口令哈希（任选一种方式）：
--        · Python：python -c "import bcrypt,getpass;print(bcrypt.hashpw(getpass.getpass().encode(),bcrypt.gensalt(10)).decode())"
--        · 或使用在线/离线 BCrypt 工具，cost 取 10
--   2) 将生成的哈希写入库中：
--        UPDATE t_user SET password = '上一步生成的哈希', can_upload = 1, can_manage = 1
--         WHERE username = 'admin';
--   3) 首次登录后建议再次更换为独立强口令，并核对 t_user 表中不存在其他可登录账号。
INSERT IGNORE INTO t_user (username, password, nickname, role, can_upload, can_manage) VALUES
('admin', '__BCRYPT_HASH_REPLACE_ME__', '管理员', 'admin', 1, 1);

-- 默认分类
INSERT INTO t_category (name, description, sort_order) VALUES
('风景', '自然风光与城市景色', 1),
('人像', '人物肖像摄影', 2),
('街拍', '街头纪实与人文', 3),
('建筑', '建筑空间美学', 4),
('自然', '动植物与自然生态', 5);
