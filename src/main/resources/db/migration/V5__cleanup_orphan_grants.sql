-- ============================================
-- V5：清理历史孤儿数据（授权元组 / 合集协作者 / 分享 / 标签关联 / 合集照片关联）
--
-- 背景（P1-6）：这些表之间没有外键约束，而历史版本的删除路径没有清理派生关系，
-- 于是库里可能留下指向"已删除对象"的行：
--   · t_auth_tuple 孤儿授权：后台仍显示、管理员无法判断是否生效；
--   · t_collection_member 幽灵成员：一旦用户/合集 id 被复用即自动获得写权限；
--   · t_share_link 死分享：指向已删照片/合集；
--   · t_photo_tag / t_collection_photos 悬空关联：污染标签计数与合集照片数。
--
-- 本迁移只删除"指向不存在实体"的行，不触碰任何仍然有效的授权/关联。
-- 代码侧已同步补上删除路径的清理逻辑，本迁移用于追平存量数据。
-- ============================================

-- 1. 授权元组：主体或对象已不存在
DELETE t
FROM t_auth_tuple t
WHERE (t.subject_type = 'user'
    AND NOT EXISTS (SELECT 1 FROM t_user u WHERE u.id = t.subject_id))
   OR (t.object_type = 'photo'
    AND NOT EXISTS (SELECT 1 FROM t_photo p WHERE p.id = t.object_id))
   OR (t.object_type = 'collection'
    AND NOT EXISTS (SELECT 1 FROM t_collection c WHERE c.id = t.object_id))
   OR (t.object_type = 'category'
    AND NOT EXISTS (SELECT 1 FROM t_category g WHERE g.id = t.object_id))
   OR (t.object_type = 'tag'
    AND NOT EXISTS (SELECT 1 FROM t_tag g WHERE g.id = t.object_id));

-- 2. 合集协作者：合集或用户已不存在
DELETE m
FROM t_collection_member m
WHERE NOT EXISTS (SELECT 1 FROM t_collection c WHERE c.id = m.collection_id)
   OR NOT EXISTS (SELECT 1 FROM t_user u WHERE u.id = m.user_id);

-- 3. 分享链接：指向的照片或合集已不存在
DELETE s
FROM t_share_link s
WHERE (s.photo_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM t_photo p WHERE p.id = s.photo_id))
   OR (s.target_type = 'collection'
    AND s.target_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM t_collection c WHERE c.id = s.target_id));

-- 4. 标签关联：照片或标签已不存在
DELETE pt
FROM t_photo_tag pt
WHERE NOT EXISTS (SELECT 1 FROM t_photo p WHERE p.id = pt.photo_id)
   OR NOT EXISTS (SELECT 1 FROM t_tag g WHERE g.id = pt.tag_id);

-- 5. 合集照片关联：照片或合集已不存在
DELETE cp
FROM t_collection_photos cp
WHERE NOT EXISTS (SELECT 1 FROM t_photo p WHERE p.id = cp.photo_id)
   OR NOT EXISTS (SELECT 1 FROM t_collection c WHERE c.id = cp.collection_id);

-- 6. 清理结果核对（输出到迁移日志）
SELECT 'auth_tuple' AS 表, COUNT(*) AS 剩余行数 FROM t_auth_tuple
UNION ALL SELECT 'collection_member', COUNT(*) FROM t_collection_member
UNION ALL SELECT 'share_link', COUNT(*) FROM t_share_link
UNION ALL SELECT 'photo_tag', COUNT(*) FROM t_photo_tag
UNION ALL SELECT 'collection_photos', COUNT(*) FROM t_collection_photos;
