-- ============================================
-- V7：逆地理编码缓存
--
-- 背景：t_photo 只存 GPS 经纬度，没有地名。年度海报需要"去过哪些地方、
-- 在哪里拍得最多"，因此接入腾讯位置服务的逆地理编码。该接口有频率与配额限制，
-- 同一坐标反复调用既慢又浪费额度，这里按"经度保留 2 位、纬度保留 2 位"
-- （约 1km 精度，城市级归属足够）缓存结果。
--
-- 说明：
--   1) 只做缓存，不是权威数据；清空后会自动重新解析。
--   2) api_status 记录上游返回状态，便于排查"为什么某处一直是未知地点"。
-- ============================================

CREATE TABLE IF NOT EXISTS t_geo_cache (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    coord_key   VARCHAR(40)  NOT NULL COMMENT '坐标键，如 39.90,116.40',
    region      VARCHAR(120) DEFAULT NULL COMMENT '行政区域名称，如 北京市朝阳区',
    api_status  INT          DEFAULT NULL COMMENT '腾讯接口返回的 status，0 为成功',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geo_coord (coord_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逆地理编码结果缓存';
