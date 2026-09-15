package com.photoalbum.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 照片访问地址解析：把"存储路径"转成"当前调用者可用的访问地址"
 *
 * 授权模型（不动存储桶策略的前提下闭合存储层授权）：
 *   · 公开照片：沿用对象直链（可被 CDN 与浏览器缓存，性能最优）；
 *   · 私密照片：对象级 ACL 置为 private（匿名直链不可访问），接口改为签发短期预签名地址，
 *     有效期内可访问、过期即失效，链接外泄的风险窗口被压缩到分钟级。
 *
 * 注意：对象级 ACL 仅对该对象生效，不改变存储桶的公共读策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoUrlResolver {

    private final COSClient cosClient;

    @Value("${cos.bucket}")
    private String cosBucket;

    @Value("${cos.domain}")
    private String cosDomain;

    /** 本地存储访问前缀（非对象存储路径使用） */
    @Value("${file.access-url-prefix:/files/}")
    private String accessUrlPrefix;

    /** 私密照片预签名地址有效期（分钟），可通过 COS_PRESIGNED_TTL_MINUTES 覆盖 */
    @Value("${cos.presigned-ttl-minutes:60}")
    private long presignedTtlMinutes;

    /** 是否对私密照片启用对象级私有 ACL（存储桶 ACL 功能不可用时设为 false） */
    @Value("${cos.private-acl-enabled:true}")
    private boolean privateAclEnabled;

    /** 解析原图访问地址 */
    public String resolve(String storedUrl, Integer isPrivate) {
        if (storedUrl == null || storedUrl.isBlank()) {
            return storedUrl;
        }
        String url = storedUrl.startsWith("http") ? storedUrl : accessUrlPrefix + storedUrl;
        if (!isPrivate(isPrivate) || !isCosUrl(url)) {
            return url;
        }
        return presign(url);
    }

    /** 解析缩略图访问地址（空值返回 null） */
    public String resolveThumbnail(String storedUrl, Integer isPrivate) {
        if (storedUrl == null || storedUrl.isBlank()) {
            return null;
        }
        return resolve(storedUrl, isPrivate);
    }

    /**
     * 把对象 ACL 同步为与私密标记一致（私密 → private，公开 → public-read）
     *
     * 上传与切换私密标记时调用；失败只告警不阻断业务（并发或权限不足时仍应能完成业务动作）。
     */
    public void applyObjectAcl(String storedUrl, Integer isPrivate) {
        if (!privateAclEnabled || storedUrl == null || storedUrl.isBlank() || !isCosUrl(storedUrl)) {
            return;
        }
        CannedAccessControlList acl = isPrivate(isPrivate)
                ? CannedAccessControlList.Private
                : CannedAccessControlList.PublicRead;
        try {
            cosClient.setObjectAcl(cosBucket, extractKey(storedUrl), acl);
        } catch (Exception e) {
            log.warn("对象 ACL 设置失败（不影响业务）: url={}, acl={}, err={}", storedUrl, acl, e.getMessage());
        }
    }

    /** 是否对私密照片启用了对象级私有 ACL */
    public boolean isPrivateAclEnabled() {
        return privateAclEnabled;
    }

    /** 对象内容（字节 + MIME 类型） */
    public record ObjectData(byte[] bytes, String contentType) {
    }

    /**
     * 读取对象存储中的对象字节
     *
     * 供分享卡片封面使用：社交平台抓取缩略图时**不跟随 302 跳转**，
     * og:image 必须是一个直接返回图片（200）的同域地址，因此这里由服务端代取字节再直出。
     * 非对象存储路径（本地 /files/）不支持，返回 null 由调用方退回默认图。
     */
    public ObjectData readObject(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank() || !isCosUrl(storedUrl)) {
            return null;
        }
        String key = extractKey(storedUrl);
        try {
            com.qcloud.cos.model.COSObject object = cosClient.getObject(cosBucket, key);
            java.io.InputStream in = object.getObjectContent();
            byte[] bytes = in.readAllBytes();
            String contentType = object.getObjectMetadata() == null
                    ? null : object.getObjectMetadata().getContentType();
            // 关闭动作放在读取之后单独处理：COS 的流关闭时会回收 HTTP 连接，
            // 这一步失败不应该把已经读到的图片一起丢掉（否则卡片会退回默认图）
            closeQuietly(in);
            return new ObjectData(bytes, (contentType == null || contentType.isBlank())
                    ? "image/jpeg" : contentType);
        } catch (Exception e) {
            log.warn("对象读取失败（分享卡片封面）: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    private void closeQuietly(java.io.InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception e) {
            log.debug("对象流关闭失败（不影响已读取内容）: {}", e.getMessage());
        }
    }

    private String presign(String cosUrl) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + presignedTtlMinutes * 60_000L);
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(cosBucket, extractKey(cosUrl));
            request.setExpiration(expiration);
            return cosClient.generatePresignedUrl(request).toString();
        } catch (Exception e) {
            log.warn("预签名地址生成失败，退回直链: url={}, err={}", cosUrl, e.getMessage());
            return cosUrl;
        }
    }

    private boolean isPrivate(Integer isPrivate) {
        return isPrivate != null && isPrivate == 1;
    }

    private boolean isCosUrl(String url) {
        return url.startsWith(cosDomain);
    }

    /** 从完整地址提取对象键 */
    private String extractKey(String url) {
        return url.replace(cosDomain + "/", "");
    }
}
