package com.photoalbum.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 登录限流器（内存实现）
 * 每个来源每分钟最多 5 次尝试，超限后锁定 15 分钟
 *
 * 安全说明（修复 V07 内存泄漏部分）：
 *   · 限流键做规范化与长度截断，避免超长/畸形键（伪造来源地址头）撑大内存；
 *   · 增加容量上限与定期清理，过期尝试记录与已失效锁定记录会被回收，
 *     避免长期运行下两个映射表无界增长形成拒绝服务隐患。
 */
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 60_000;         // 1 分钟窗口
    private static final long LOCK_DURATION_MS = 900_000; // 锁定 15 分钟

    /** 键容量上限：超过该规模即触发一次清理 */
    private static final int MAX_KEYS = 10_000;
    /** 两次清理之间的最小间隔 */
    private static final long CLEANUP_INTERVAL_MS = 60_000;
    /** 限流键最大长度（IPv6 文本形式最长 45 字符） */
    private static final int MAX_KEY_LENGTH = 45;

    private static final String UNKNOWN_KEY = "unknown";

    private static final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> attempts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private static volatile long lastCleanupAt = System.currentTimeMillis();

    /**
     * 尝试登录。返回 true 表示允许，false 表示被限流。
     */
    public static synchronized boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        String key = normalize(ip);
        cleanupIfNeeded(now);

        // 检查是否在锁定期内
        Long lockExpiry = lockedUntil.get(key);
        if (lockExpiry != null) {
            if (now < lockExpiry) {
                return false;
            }
            lockedUntil.remove(key);
        }

        // 获取该来源的尝试记录
        ConcurrentLinkedDeque<Long> deque = attempts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // 清理窗口外的旧记录
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }

        // 记录本次尝试
        deque.offerLast(now);

        // 判断是否超限
        if (deque.size() > MAX_ATTEMPTS) {
            lockedUntil.put(key, now + LOCK_DURATION_MS);
            attempts.remove(key);
            return false;
        }

        return true;
    }

    /** 登录成功后清除该来源的记录 */
    public static synchronized void clear(String ip) {
        String key = normalize(ip);
        attempts.remove(key);
        lockedUntil.remove(key);
    }

    /**
     * 规范化限流键：去空白、限长，并统一空值键
     */
    private static String normalize(String ip) {
        if (ip == null) {
            return UNKNOWN_KEY;
        }
        String key = ip.trim();
        if (key.isEmpty()) {
            return UNKNOWN_KEY;
        }
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
    }

    /**
     * 按需清理：到达清理间隔或键规模超限时，回收过期记录
     */
    private static void cleanupIfNeeded(long now) {
        boolean intervalReached = now - lastCleanupAt >= CLEANUP_INTERVAL_MS;
        boolean tooManyKeys = attempts.size() + lockedUntil.size() >= MAX_KEYS;
        if (!intervalReached && !tooManyKeys) {
            return;
        }
        lastCleanupAt = now;

        // 锁定已失效的记录
        lockedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);

        // 尝试记录全部落在窗口外的键
        attempts.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> deque = entry.getValue();
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
    }
}
