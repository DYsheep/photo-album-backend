package com.photoalbum.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.entity.CollectionMember;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.CollectionMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 合集级授权守卫（供 @PreAuthorize 以 SpEL 调用，实现对象级判定）
 *
 * 用法：{@code @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#id, authentication)")}
 *
 * 这样"能否管理这个合集"就在声明式校验里表达，而不是散落在业务代码中靠人工记得检查。
 */
@Component("collectionAccess")
@RequiredArgsConstructor
public class CollectionAccessGuard {

    private final CollectionMemberMapper memberMapper;

    /** 是否可管理指定合集：具备内容管理权限，或为该合集的协作者 */
    public boolean canManageCollection(Long collectionId, Authentication authentication) {
        User user = principal(authentication);
        if (user == null) {
            return false;
        }
        if (UserAuthorities.hasManageAccess(user)) {
            return true;
        }
        return isMember(collectionId, user);
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
