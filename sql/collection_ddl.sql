-- 合集数据库 DDL（请在 MySQL 中执行）
-- 数据库: photo_album

CREATE TABLE IF NOT EXISTS t_collection (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  cover_photo_id BIGINT,
  sort_order INT DEFAULT 0,
  is_published TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_collection_photos (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  collection_id BIGINT NOT NULL,
  photo_id BIGINT NOT NULL,
  sort_order INT DEFAULT 0,
  UNIQUE KEY uk_collection_photo (collection_id, photo_id)
);
