package com.photoalbum.controller;

import com.photoalbum.dto.ShareCard;
import com.photoalbum.service.ShareCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 分享页服务端渲染：让分享链接在社交平台显示成卡片
 *
 * 为什么需要服务端渲染：微信 / QQ / 微博等平台抓取链接时只读原始 HTML、不执行 JavaScript，
 * 前端 SPA 里动态设置的 title 与图片对它们不可见（现状是分享出去只有一个光秃秃的链接）。
 * 本控制器把卡片元数据（og:*）直接写进 HTML 头部后再返回：
 *   · 抓取器读到卡片信息 → 显示为带封面与标题的卡片；
 *   · 浏览器拿到的是同一份 SPA 页面 → 前端路由照常接管，体验与原来完全一致。
 *
 * 模板取自前端构建产物 index.html（其中的卡片元数据块被替换），
 * 因此前端构建后自动同步，无需在两端各维护一份 HTML。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SharePageController {

    /** 前端 index.html 中卡片元数据块的起止标记（替换时不保留标记本身） */
    private static final String BLOCK_START = "<!-- share-card:start -->";
    private static final String BLOCK_END = "<!-- share-card:end -->";

    /** 模板探测顺序：配置项 > 与后端同级的 ../frontend/dist/index.html > 部署默认路径 */
    private static final String RELATIVE_TEMPLATE = "../frontend/dist/index.html";
    private static final String DEFAULT_TEMPLATE = "/opt/photo-album/frontend/dist/index.html";

    /** 挂载点：卡片封面以隐藏图的形式插在它前面（部分平台取"页面第一张图"而非 og:image） */
    private static final String BODY_ANCHOR = "<div id=\"app\">";

    private final ShareCardService shareCardService;

    @Value("${share.page-template:}")
    private String configuredTemplate;

    /** 模板缓存（构建产物不常变，按修改时间失效） */
    private volatile String templateCache;
    private volatile long templateCacheStamp = -1L;
    private volatile boolean templateMissingLogged;

    @GetMapping(value = "/share/{code}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> sharePage(@PathVariable String code) {
        ShareCard card = cardOf(code);
        String metaBlock = renderMetaBlock(card);

        String template = loadTemplate();
        if (template == null) {
            // 模板不可用时返回 503，由 Nginx 回退为静态 SPA（页面照常可用，仅没有卡片）
            return ResponseEntity.status(503)
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .cacheControl(CacheControl.noStore())
                    .body(metaBlock);
        }

        String html = injectCoverImage(injectMeta(template, metaBlock), card.imageUrl());
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noCache())
                .body(html);
    }

    // ============================================================
    // 卡片元数据
    // ============================================================

    private ShareCard cardOf(String code) {
        try {
            ShareCard card = shareCardService.cardOf(code);
            if (card != null) {
                return card;
            }
        } catch (Exception e) {
            // 卡片信息只影响分享预览，绝不能因为它报错而打不开分享页
            log.warn("分享卡片信息读取失败，退回通用卡片: code={}, err={}", code, e.getMessage());
        }
        return new ShareCard(ShareCardService.SITE_NAME, ShareCardService.SITE_DESCRIPTION, null, null);
    }

    /**
     * 生成 og:* 元数据块（包级可见以便单测）
     *
     * 注意：meta() 内部会统一转义，这里必须传原始值，否则会出现二次转义（&amp;lt;）。
     */
    static String renderMetaBlock(ShareCard card) {
        String title = card.title() == null ? "" : card.title().trim();
        String description = card.description() == null ? "" : card.description();
        String siteTitle = title.isEmpty() ? ShareCardService.SITE_NAME : escape(title) + " - " + ShareCardService.SITE_NAME;

        StringBuilder sb = new StringBuilder();
        sb.append(BLOCK_START).append('\n');
        sb.append("    <title>").append(siteTitle).append("</title>\n");
        sb.append(meta("name", "description", description));
        sb.append(meta("property", "og:type", "website"));
        sb.append(meta("property", "og:site_name", ShareCardService.SITE_NAME));
        sb.append(meta("property", "og:title", title));
        sb.append(meta("property", "og:description", description));
        if (card.url() != null && !card.url().isBlank()) {
            sb.append(meta("property", "og:url", card.url()));
        }
        if (card.imageUrl() != null && !card.imageUrl().isBlank()) {
            sb.append(meta("property", "og:image", card.imageUrl()));
        }
        sb.append(meta("name", "twitter:card", "summary_large_image"));
        sb.append(meta("name", "twitter:title", title));
        sb.append(meta("name", "twitter:description", description));
        if (card.imageUrl() != null && !card.imageUrl().isBlank()) {
            sb.append(meta("name", "twitter:image", card.imageUrl()));
        }
        sb.append("    ").append(BLOCK_END);
        return sb.toString();
    }

    static String meta(String attr, String key, String value) {
        return "    <meta " + attr + "=\"" + key + "\" content=\"" + escape(value) + "\" />\n";
    }

    /**
     * 把元数据块写进模板
     *
     * 优先整块替换标记之间的内容（前端模板自带标记时最干净）；
     * 标记缺失时（部分构建工具会去掉 HTML 注释）先清掉模板里原有的同类标签再插入，
     * 避免页面里出现两套卡片信息、导致平台取到站点默认标题。
     */
    static String injectMeta(String template, String metaBlock) {
        int start = template.indexOf(BLOCK_START);
        int end = template.indexOf(BLOCK_END);
        if (start >= 0 && end > start) {
            return template.substring(0, start) + metaBlock + template.substring(end + BLOCK_END.length());
        }
        String cleaned = stripCardTags(template);
        int headEnd = cleaned.indexOf("</head>");
        if (headEnd < 0) {
            return cleaned;
        }
        log.debug("分享页模板缺少卡片元数据标记，已清除同类标签后插入");
        return cleaned.substring(0, headEnd) + metaBlock + "\n  " + cleaned.substring(headEnd);
    }

    /**
     * 把封面图以隐藏 img 的形式插到挂载点之前
     *
     * 除了 og:image，部分平台（如 QQ）取的是"页面里第一张图"；SPA 页面本身没有 img 标签，
     * 这里补一张隐藏的（不参与布局、不可见），两类抓取策略都能拿到图。
     */
    static String injectCoverImage(String html, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return html;
        }
        int anchor = html.indexOf(BODY_ANCHOR);
        if (anchor < 0) {
            return html;
        }
        String tag = "<img src=\"" + escape(imageUrl) + "\" alt=\"\" aria-hidden=\"true\" "
                + "style=\"position:absolute;left:-9999px;top:0;opacity:0\" />";
        return html.substring(0, anchor) + tag + html.substring(anchor);
    }

    /** 移除模板中原有的标题与卡片类元标签（含 title 的文本内容） */
    static String stripCardTags(String html) {
        String result = html;
        int titleStart = result.indexOf("<title");
        while (titleStart >= 0) {
            int titleEnd = result.indexOf("</title>", titleStart);
            if (titleEnd < 0) {
                break;
            }
            result = result.substring(0, titleStart) + result.substring(titleEnd + "</title>".length());
            titleStart = result.indexOf("<title");
        }

        StringBuilder sb = new StringBuilder(result.length());
        int index = 0;
        while (true) {
            int lt = result.indexOf('<', index);
            if (lt < 0) {
                sb.append(result, index, result.length());
                break;
            }
            int gt = result.indexOf('>', lt);
            if (gt < 0) {
                sb.append(result, index, result.length());
                break;
            }
            sb.append(result, index, lt);
            String tag = result.substring(lt, gt + 1);
            if (!isCardMeta(tag)) {
                sb.append(tag);
            }
            index = gt + 1;
        }
        return sb.toString();
    }

    /** 是否属于卡片信息标签（og:* / twitter:* / description） */
    static boolean isCardMeta(String tag) {
        String lower = tag.toLowerCase();
        return lower.startsWith("<meta")
                && (lower.contains("og:") || lower.contains("twitter:") || lower.contains("name=\"description\"")
                    || lower.contains("name='description'"));
    }

    // ============================================================
    // 模板加载
    // ============================================================

    private String loadTemplate() {
        for (String path : templateCandidates()) {
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            long stamp = file.lastModified();
            if (templateCache != null && stamp == templateCacheStamp) {
                return templateCache;
            }
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                templateCache = content;
                templateCacheStamp = stamp;
                templateMissingLogged = false;
                log.debug("分享页模板已加载: {}", path);
                return content;
            } catch (Exception e) {
                log.warn("分享页模板读取失败: path={}, err={}", path, e.getMessage());
            }
        }
        if (!templateMissingLogged) {
            templateMissingLogged = true;
            log.error("未找到分享页模板（前端构建产物 index.html），分享页将退回静态页面。"
                    + "请确认前端已构建，或设置 SHARE_PAGE_TEMPLATE。候选路径: {}",
                    String.join(", ", templateCandidates()));
        }
        // 读不到模板时沿用上一次成功的内容（前端正在构建时仍可服务）
        return templateCache;
    }

    private java.util.List<String> templateCandidates() {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            candidates.add(configuredTemplate.trim());
        }
        Path cwd = Paths.get("").toAbsolutePath();
        candidates.add(cwd.resolve(RELATIVE_TEMPLATE).normalize().toString());
        candidates.add(DEFAULT_TEMPLATE);
        return candidates;
    }

    /** HTML 文本与属性值转义（先转义 & 再转义其余，避免二次转义） */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
