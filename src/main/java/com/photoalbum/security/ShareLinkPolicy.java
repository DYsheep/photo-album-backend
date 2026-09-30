package com.photoalbum.security;

import com.photoalbum.entity.ShareLink;

/**
 * 分享链接的访问策略（创建侧与运行侧共用的唯一判定口径）
 *
 * 硬约束：**无口令的分享不得包含私密内容**。
 *   私密照片对象虽然置了私有 ACL，但分享卡片封面这类接口是由服务端凭据代取对象字节后直出的，
 *   不受对象级 ACL 约束；因此只要"含私密"与"无口令"能同时成立，
 *   拿到分享码的人（分享码会印在二维码上、随卡片转发扩散）就等于拿到了私密照片。
 *
 * 两侧都要判：
 *   创建侧（ShareServiceImpl）—— 无口令时拒绝创建，避免产生新的高危链接；
 *   运行侧（本类）—— 对本次修复上线前已存在的历史链接做兜底，
 *   即使其 include_private 仍为 1，也按"无口令即不含私密"处理。
 */
public final class ShareLinkPolicy {

    private ShareLinkPolicy() {
    }

    /** 分享链接是否设置了访问口令 */
    public static boolean hasAccessCode(ShareLink link) {
        return link != null && link.getAccessCode() != null && !link.getAccessCode().isBlank();
    }

    /**
     * 实际生效的"是否包含私密照片"
     *
     * 注意不能直接读 link.getIncludePrivate()：历史数据里存在"含私密但无口令"的链接，
     * 直接采信会让本次修复只对新链接生效。
     */
    public static boolean effectiveIncludePrivate(ShareLink link) {
        return link != null
                && link.getIncludePrivate() != null
                && link.getIncludePrivate() == 1
                && hasAccessCode(link);
    }
}
