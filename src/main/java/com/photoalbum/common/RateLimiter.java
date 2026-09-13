package com.photoalbum.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 通用接口限流器（内存实现）
 *
 * 用法：按业务维度构造限流键（如 login:IP、upload:用户ID、like:IP），分别设置阈值与窗口。
 * 设计要点：
 *   · 每个键记录自己的统计窗口，长窗口（如每小时上传次数）不会被短窗口的清理逻辑误清；
 *   · 键做规范化与长度截断，避免超长/畸形键（伪造来源地址头）撑大内存；
 *   · 容量上限 + 定期清理，回收过期尝试与失效锁定记录，避免无界增长；
 *   · 单机内存实现，多实例部署时应替换为集中式存储（Redis）并保持同一套键规范。
 */
public final class RateLimiter {

    /** 键容量上限：超过该规模即触发一次清理 */
    private static final int MAX_KEYS = 10_000;
    /** 两次清理之间的最小间隔 */
    private static final long CLEANUP_INTERVAL_MS = 60_000;
    /** 限流键最大长度 */
    private static final int MAX_KEY_LENGTH = 120;

    /** 尝试记录：时间戳队列 + 该键使用的统计窗口 */
    private record Window(ConcurrentLinkedDeque<Long> timestamps, long windowMs) {
    }

    private static final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private static volatile long lastCleanupAt = System.currentTimeMillis();

    private RateLimiter() {
    }

    /**
     * 尝试获取一次操作许可
     *
     * @param key            限流键（建议形如 "业务:主体"，如 "login:1.2.3.4"）
     * @param maxAttempts    窗口内允许的次数
     * @param windowMs       统计窗口（毫秒）
     * @param lockDurationMs 超限后的锁定时长（毫秒）
     * @return true=允许，false=已限流
     */
    public static synchronized boolean tryAcquire(String key, int maxAttempts, long windowMs, long lockDurationMs) {
        long now = System.currentTimeMillis();
        String normalizedKey = normalize(key);
        cleanupIfNeeded(now);

        Long lockExpiry = lockedUntil.get(normalizedKey);
        if (lockExpiry != null) {
            if (now < lockExpiry) {
                return false;
            }
            lockedUntil.remove(normalizedKey);
        }

        Window window = attempts.get(normalizedKey);
        if (window == null || window.windowMs() != windowMs) {
            window = new Window(new ConcurrentLinkedDeque<>(), windowMs);
            attempts.put(normalizedKey, window);
        }
        ConcurrentLinkedDeque<Long> timestamps = window.timestamps();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }
        timestamps.offerLast(now);

        if (timestamps.size() > maxAttempts) {
            lockedUntil.put(normalizedKey, now + lockDurationMs);
            attempts.remove(normalizedKey);
            return false;
        }
        return true;
    }

    /** 清除某个键的记录（例如登录成功后重置失败计数） */
    public static synchronized void clear(String key) {
        String normalizedKey = normalize(key);
        attempts.remove(normalizedKey);
        lockedUntil.remove(normalizedKey);
    }

    /** 距离解除锁定还需的秒数（未锁定时返回 0） */
    public static long retryAfterSeconds(String key) {
        Long lockExpiry = lockedUntil.get(normalize(key));
        if (lockExpiry == null) {
            return 0;
        }
        long remain = lockExpiry - System.currentTimeMillis();
        return remain > 0 ? (remain + 999) / 1000 : 0;
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) {
            return "unknown";
        }
        String value = key.trim();
        return value.length() > MAX_KEY_LENGTH ? value.substring(0, MAX_KEY_LENGTH) : value;
    }

    /** 按需清理：到达清理间隔或键规模超限时回收过期记录 */
    private static void cleanupIfNeeded(long now) {
        boolean intervalReached = now - lastCleanupAt >= CLEANUP_INTERVAL_MS;
        boolean tooManyKeys = attempts.size() + lockedUntil.size() >= MAX_KEYS;
        if (!intervalReached && !tooManyKeys) {
            return;
        }
        lastCleanupAt = now;
        lockedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        attempts.entrySet().removeIf(entry -> {
            Window window = entry.getValue();
            ConcurrentLinkedDeque<Long> timestamps = window.timestamps();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > window.windowMs()) {
                timestamps.pollFirst();
            }
            return timestamps.isEmpty();
        });
    }
}
