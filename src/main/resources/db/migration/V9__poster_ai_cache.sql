-- ============================================
-- V9：年度海报的 AI 素材缓存
--
-- A+C 方案：
--   A. AI 生成海报背景（每年一张，生成后复用，避免每次点"生成海报"都计费）
--   C. DeepSeek 生成一句年度文案（纯文本，不存在渲染不准的问题）
--
-- 说明：一行对应一年；背景与文案分开存，任意一方生成失败不影响另一方。
-- ============================================

CREATE TABLE IF NOT EXISTS t_poster_ai (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    year             INT          NOT NULL COMMENT '年份',
    background_url   VARCHAR(500) DEFAULT NULL COMMENT '背景图地址（COS 直链）',
    background_prompt TEXT        DEFAULT NULL COMMENT '生成背景所用的 prompt，便于复现',
    copy_text        VARCHAR(200) DEFAULT NULL COMMENT 'AI 文案（一句年度总结）',
    image_model      VARCHAR(80)  DEFAULT NULL COMMENT '生成背景所用的模型',
    created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_poster_ai_year (year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年度海报 AI 素材缓存';
