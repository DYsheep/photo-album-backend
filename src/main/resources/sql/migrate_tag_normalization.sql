-- ============================================
-- 迁移脚本：标签规范化（逗号分隔字符串 → 关联表）
-- 【手工追平脚本 · 一次性】用于既有数据库追平结构差异（Flyway 接入前的历史脚本）。
-- 后续结构变更请新增 src/main/resources/db/migration/Vn__*.sql，由 Flyway 自动执行。
-- 适用：已在运行的 photo_album 库
-- ============================================

USE photo_album;

-- --------------------------------------------
-- 1. 建表（标签权威数据与照片关联）
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS t_tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50) NOT NULL                COMMENT '标签名（唯一）',
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

CREATE TABLE IF NOT EXISTS t_photo_tag (
    id       BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    photo_id BIGINT NOT NULL                COMMENT '照片ID',
    tag_id   BIGINT NOT NULL                COMMENT '标签ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_photo_tag (photo_id, tag_id),
    INDEX idx_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照片标签关联表';

-- 授权对象类型补充 tag 维度（仅注释更新，便于后续维护）
ALTER TABLE t_user_permission
    MODIFY COLUMN target_type VARCHAR(20) NOT NULL
        COMMENT '授权对象类型: photo/collection/category/tag/global';

-- --------------------------------------------
-- 2. 存量回填：把 t_photo.tags 逗号串拆进关联表
--    说明：拆分上限为每张照片 20 个标签（本类站点足够；超出部分请手工补录）
--    t_photo.tags 保留为展示用冗余字段，写路径会继续双写，二者保持一致
-- --------------------------------------------
INSERT IGNORE INTO t_tag(name)
SELECT DISTINCT tag_name FROM (
    SELECT TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p.tags, ',', n.n), ',', -1)) AS tag_name
    FROM t_photo p
    JOIN (
        SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
        UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
        UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
        UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20
    ) n ON n.n <= 1 + LENGTH(p.tags) - LENGTH(REPLACE(p.tags, ',', ''))
    WHERE p.tags IS NOT NULL AND p.tags <> ''
) x
WHERE x.tag_name <> '';

INSERT IGNORE INTO t_photo_tag(photo_id, tag_id)
SELECT DISTINCT p.id, t.id
FROM t_photo p
JOIN (
    SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
    UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
    UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20
) n ON n.n <= 1 + LENGTH(p.tags) - LENGTH(REPLACE(p.tags, ',', ''))
JOIN t_tag t ON t.name = TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p.tags, ',', n.n), ',', -1))
WHERE p.tags IS NOT NULL AND p.tags <> '';

-- --------------------------------------------
-- 3. 核对
-- --------------------------------------------
-- 3.1 回填结果概览
SELECT (SELECT COUNT(*) FROM t_tag) AS 标签数,
       (SELECT COUNT(*) FROM t_photo_tag) AS 关联数,
       (SELECT COUNT(*) FROM t_photo WHERE tags IS NOT NULL AND tags <> '') AS 有标签的照片数;

-- 3.2 引用数 Top 20（可与后台标签管理页的计数对照）
SELECT t.name AS 标签, COUNT(pt.photo_id) AS 引用数
FROM t_tag t LEFT JOIN t_photo_tag pt ON pt.tag_id = t.id
GROUP BY t.id, t.name
ORDER BY 引用数 DESC
LIMIT 20;

-- 3.3 拆分一致性抽查（应为空：关联表里没有、但照片串里有的标签）
SELECT p.id AS 照片ID, p.tags AS 标签串
FROM t_photo p
WHERE p.tags IS NOT NULL AND p.tags <> ''
  AND EXISTS (
      SELECT 1 FROM (
          SELECT TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p2.tags, ',', n.n), ',', -1)) AS tag_name
          FROM t_photo p2
          JOIN (
              SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
              UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
          ) n ON n.n <= 1 + LENGTH(p2.tags) - LENGTH(REPLACE(p2.tags, ',', ''))
          WHERE p2.id = p.id
      ) x
      WHERE x.tag_name <> '' AND x.tag_name NOT IN (SELECT name FROM t_tag)
  )
LIMIT 20;

-- 3.4 提示：如回填后仍有计数异常，可在后台调用 POST /api/admin/tags/rebuild
--      （以 t_photo.tags 展示字段为准重建关联表）。
