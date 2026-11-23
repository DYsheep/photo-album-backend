package com.photoalbum.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.entity.CollectionMember;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.CollectionMemberMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 合集级授权守卫（供 @PreAuthorize 以 SpEL 调用，实现对象级判定）
 *
 * 用法：{@code @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#id, authentication)")}
 *
 * 判定**完全委托**给 {@link AccessPolicy#canManageCollection}，与 Service 层的写入口
 * 用同一套规则（数据范围 + 创建人 + 协作者 − deny）。
 * 此前本类自己实现了一套（"具备管理能力位即放行"），导致控制器放行、服务层再拒绝的
 * 双口径：同一个请求在 @PreAuthorize 处通过、在业务层变成 404，排查时极易误判为越权。
 */
@Component("collectionAccess")
@RequiredArgsConstructor
public class CollectionAccessGuard {

    private final AccessPolicy accessPolicy;
    private final PhotoCollectionMapper collectionMapper;
    private final CollectionMemberMapper memberMapper;

    /** 是否可管理指定合集：与 Service 层同一判定 */
    public boolean canManageCollection(Long collectionId, Authentication authentication) {
        User user = principal(authentication);
        if (user == null || collectionId == null) {
            return false;
        }
        return accessPolicy.canManageCollection(user, collectionMapper.selectById(collectionId));
    }

    /** 指定账号是否为该合集的协作者 */
    public boolean isMember(Long collectionId, Authentication authentication) {
        return isMember(collectionId, principal(authentication));
    }

    /** 指定账号是否为该合集的协作者（内部使用） */
    public boolean isMember(Long collectionId, User user) {
        if (collectionId == null || user == null || user.getId() == null) {
            return false;
        }
        return memberMapper.selectCount(new LambdaQueryWrapper<CollectionMember>()
                .eq(CollectionMember::getCollectionId, collectionId)
                .eq(CollectionMember::getUserId, user.getId())) > 0;
    }

    private User principal(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof User ? (User) principal : null;
    }
}
