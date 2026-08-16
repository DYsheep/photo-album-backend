package com.photoalbum.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 登录限流器（内存实现）
 * 每个 IP 每分钟最多 5 次尝试，超限后锁定 15 分钟
 */
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 60_000;        // 1 分钟窗口
    private static final long LOCK_DURATION_MS = 900_000; // 锁定 15 分钟

    private static final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> attempts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lockedUntil = new ConcurrentHashMap<>();

    /**
     * 尝试登录。返回 true 表示允许，false 表示被限流。
     */
    public static synchronized boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();

        // 检查是否在锁定期内
        Long lockExpiry = lockedUntil.get(ip);
        if (lockExpiry != null && now < lockExpiry) {
            return false;
        }
        if (lockExpiry != null && now >= lockExpiry) {
            lockedUntil.remove(ip);
        }

        // 获取该 IP 的尝试记录
        ConcurrentLinkedDeque<Long> deque = attempts.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());

        // 清理窗口外的旧记录
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }

        // 记录本次尝试
        deque.offerLast(now);

        // 判断是否超限
        if (deque.size() > MAX_ATTEMPTS) {
            lockedUntil.put(ip, now + LOCK_DURATION_MS);
            attempts.remove(ip);
            return false;
        }

        return true;
    }

    /** 登录成功后清除该 IP 的记录 */
    public static void clear(String ip) {
        attempts.remove(ip);
        lockedUntil.remove(ip);
    }
}
