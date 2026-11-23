-- ============================================
-- V6：约束补齐与迁移遗留标注
--
-- 1) 照片分享的 target_type / target_id 回填
--    V3 只对"当时已存在"的行做了 target_id = photo_id，而新建照片分享的代码
--    一直只写 photo_id、不写 target_*，导致 idx_share_target 对照片分享永远命中不了，
--    "一个对象一条分享" 也失去了按目标维度的唯一约束。
-- 2) t_collection_photos 补唯一键（同一合集内不允许重复照片）
-- 3) t_share_link 补唯一键（同一目标只允许一条分享）
-- 4) 旧授权表 t_user_permission 标注为废弃，避免继续被当作权威表手工修改
--
-- 说明：所有 DDL 都先查 information_schema 再执行，重复应用到已存在该索引的库不会报错。
-- 唯一键建立前先按 "保留 id 最大（最新）的那条" 去重，避免历史重复数据导致 ALTER 失败。
-- ============================================

-- --------------------------------------------
-- 1. 回填照片分享的对象字段
-- --------------------------------------------
UPDATE t_share_link
SET target_type = 'photo',
    target_id   = photo_id
WHERE target_type = 'photo'
  AND target_id IS NULL
  AND photo_id IS NOT NULL;

-- --------------------------------------------
-- 2. t_collection_photos 去重 + 唯一键
-- --------------------------------------------
DELETE cp1
FROM t_collection_photos cp1
         JOIN t_collection_photos cp2
              ON cp1.collection_id = cp2.collection_id
                  AND cp1.photo_id = cp2.photo_id
                  AND cp1.id < cp2.id;

SET @idx_exists := (SELECT COUNT(*)
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 't_collection_photos'
                      AND INDEX_NAME = 'uk_collection_photo');
SET @ddl := IF(@idx_exists = 0,
               'ALTER TABLE t_collection_photos ADD UNIQUE KEY uk_collection_photo (collection_id, photo_id)',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --------------------------------------------
-- 3. t_share_link 去重 + 唯一键
--    去重先于建索引；target_id 为 NULL 的行（理论上已由第 1 步回填）不参与去重
-- --------------------------------------------
DELETE s1
FROM t_share_link s1
         JOIN t_share_link s2
              ON s1.target_type = s2.target_type
                  AND s1.target_id = s2.target_id
                  AND s1.id < s2.id
WHERE s1.target_id IS NOT NULL;

SET @idx_exists := (SELECT COUNT(*)
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 't_share_link'
                      AND INDEX_NAME = 'uk_share_target');
SET @ddl := IF(@idx_exists = 0,
               'ALTER TABLE t_share_link ADD UNIQUE KEY uk_share_target (target_type, target_id)',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --------------------------------------------
-- 4. 旧授权表标注废弃
--    判定层只读 t_auth_tuple；继续在 t_user_permission 上"撤权"不会生效（假撤权）。
--    保留表与数据以便追溯，但用表注释明确权威表，避免后续维护再踩坑。
-- --------------------------------------------
ALTER TABLE t_user_permission
    COMMENT = '已废弃（仅保留历史数据）：授权权威表为 t_auth_tuple，在此表增删不生效';

-- --------------------------------------------
-- 5. 迁移结果核对（写入 Flyway 日志）
-- --------------------------------------------
SELECT 'share_without_target' AS 检查项, COUNT(*) AS 数量
FROM t_share_link
WHERE target_id IS NULL
UNION ALL
SELECT 'collection_photos_duplicates', COUNT(*)
FROM (SELECT collection_id, photo_id
      FROM t_collection_photos
      GROUP BY collection_id, photo_id
      HAVING COUNT(*) > 1) d
UNION ALL
SELECT 'share_target_duplicates', COUNT(*)
FROM (SELECT target_type, target_id
      FROM t_share_link
      WHERE target_id IS NOT NULL
      GROUP BY target_type, target_id
      HAVING COUNT(*) > 1) s;
