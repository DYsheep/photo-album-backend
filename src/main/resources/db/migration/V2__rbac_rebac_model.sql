-- ============================================
-- V2：权限模型化（RBAC 角色模板 + ReBAC 关系元组 + 数据范围）
--   · 功能权限：角色模板 → 能力位（新增子管理员角色 = 加一行模板，不必改代码）
--   · 数据权限：关系元组 主体×关系(allow/deny)×对象(global/photo/collection/category/tag)，deny 优先
--   · 既有 t_user_permission 条目平移为元组（W→allow，B→deny），语义等价
-- ============================================
CREATE TABLE IF NOT EXISTS t_role_template (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(30)  NOT NULL                COMMENT '角色编码（与 t_user.role 对应）',
    name        VARCHAR(50)  NOT NULL                COMMENT '角色名称',
    description VARCHAR(200) DEFAULT ''              COMMENT '角色说明',
    data_scope  VARCHAR(20)  NOT NULL DEFAULT 'OWN_AND_GRANTED' COMMENT '数据范围: ALL / OWN_AND_GRANTED',
    is_system   TINYINT      DEFAULT 0               COMMENT '内置角色: 1=不可删除',
    sort_order  INT          DEFAULT 0               COMMENT '排序',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色模板表';

CREATE TABLE IF NOT EXISTS t_role_capability (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_code  VARCHAR(30) NOT NULL                COMMENT '角色编码',
    capability VARCHAR(50) NOT NULL                COMMENT '能力位',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_cap (role_code, capability)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色能力位表';

CREATE TABLE IF NOT EXISTS t_auth_tuple (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    subject_type VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '主体类型: user/role',
    subject_id   BIGINT      NOT NULL                COMMENT '主体ID',
    relation     VARCHAR(10) NOT NULL                COMMENT '关系: allow / deny（deny 优先）',
    object_type  VARCHAR(20) NOT NULL                COMMENT '对象类型: global/photo/collection/category/tag',
    object_id    BIGINT      NOT NULL DEFAULT 0      COMMENT '对象ID（global 固定 0）',
    created_at   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT      DEFAULT NULL            COMMENT '授权操作人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tuple (subject_type, subject_id, relation, object_type, object_id),
    INDEX idx_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系元组表（数据权限）';

ALTER TABLE t_collection
    ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID（数据范围 OWN 判定）';

INSERT IGNORE INTO t_role_template (code, name, description, data_scope, is_system, sort_order) VALUES
('admin',  '超级管理员', '全站功能与数据', 'ALL', 1, 1),
('viewer', '查看者',     '可查看被授权的私密内容', 'OWN_AND_GRANTED', 1, 2),
('user',   '普通用户',   '仅浏览公开内容', 'OWN_AND_GRANTED', 1, 3);

INSERT IGNORE INTO t_role_capability (role_code, capability) VALUES
('admin', 'photo:upload'), ('admin', 'photo:manage'), ('admin', 'photo:view_private'), ('admin', 'user:manage'),
('viewer', 'photo:view_private');

INSERT IGNORE INTO t_auth_tuple (subject_type, subject_id, relation, object_type, object_id, created_at, created_by)
SELECT 'user', user_id, CASE WHEN perm_type = 'W' THEN 'allow' ELSE 'deny' END,
       target_type, target_id, created_at, created_by
FROM t_user_permission;

SELECT subject_id AS 用户, relation AS 关系, object_type AS 对象类型, object_id AS 对象ID FROM t_auth_tuple ORDER BY subject_id;
SELECT (SELECT COUNT(*) FROM t_user_permission) AS 旧表条目数, (SELECT COUNT(*) FROM t_auth_tuple) AS 新表条目数;
