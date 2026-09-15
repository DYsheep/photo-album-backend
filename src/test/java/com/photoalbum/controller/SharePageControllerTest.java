package com.photoalbum.controller;

import com.photoalbum.dto.ShareCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分享页 HTML 注入测试
 *
 * 这里验证的是"卡片信息怎么进到 HTML 里"这段最容易出错、又最影响观感的逻辑：
 * 标题里的引号会不会撑破属性、模板原有标签会不会残留出两套卡片信息。
 */
@DisplayName("SharePageController HTML 注入测试")
class SharePageControllerTest {

    private static final ShareCard CARD = new ShareCard(
            "我们的饭", "共 12 张照片",
            "https://www.dyframe.art/api/share/AbCd1234/cover",
            "https://www.dyframe.art/share/AbCd1234");

    @Test
    @DisplayName("模板带有标记块：整块替换，站内默认标题不残留")
    void replacesMarkedBlock() {
        String template = "<html><head>\n"
                + "    <!-- share-card:start -->\n"
                + "    <title>摄影相册 - Photo Album</title>\n"
                + "    <meta property=\"og:title\" content=\"摄影相册 - Photo Album\" />\n"
                + "    <!-- share-card:end -->\n"
                + "</head><body><div id=\"app\"></div></body></html>";

        String html = SharePageController.injectMeta(template, SharePageController.renderMetaBlock(CARD));

        assertThat(html).contains("og:title\" content=\"我们的饭\"");
        assertThat(html).contains("<title>我们的饭 - 摄影相册</title>");
        assertThat(html).doesNotContain("Photo Album");
        // 原页面结构必须完整保留
        assertThat(html).contains("<div id=\"app\"></div>").contains("</head>");
    }

    @Test
    @DisplayName("模板被构建工具去掉注释：先清除同类标签再插入，不出现两套卡片信息")
    void stripsExistingTagsWhenMarkerMissing() {
        String template = "<html><head>\n"
                + "    <title>摄影相册 - Photo Album</title>\n"
                + "    <meta name=\"description\" content=\"默认描述\" />\n"
                + "    <meta property=\"og:title\" content=\"摄影相册 - Photo Album\" />\n"
                + "    <meta property=\"og:image\" content=\"https://www.dyframe.art/icons/icon-512.png\" />\n"
                + "    <meta name=\"theme-color\" content=\"#1a1a2e\" />\n"
                + "    <link rel=\"manifest\" href=\"/manifest.webmanifest\" />\n"
                + "</head><body></body></html>";

        String html = SharePageController.injectMeta(template, SharePageController.renderMetaBlock(CARD));

        // 只应有一套卡片信息
        assertThat(countOccurrences(html, "og:title")).isEqualTo(1);
        assertThat(countOccurrences(html, "<title>")).isEqualTo(1);
        assertThat(countOccurrences(html, "name=\"description\"")).isEqualTo(1);
        assertThat(html).contains("og:title\" content=\"我们的饭\"");
        // 标题的文本内容必须一起清掉，否则会掉进 body 变成可见文字
        assertThat(html).doesNotContain("Photo Album");
        // 与本功能无关的头部内容不受影响
        assertThat(html).contains("theme-color").contains("manifest.webmanifest");
    }

    @Test
    @DisplayName("标题中的引号与尖括号被转义，不会撑破属性")
    void escapesQuotesAndAngles() {
        ShareCard risky = new ShareCard("a\" onload=\"alert(1)", "<b>x</b>",
                "https://www.dyframe.art/api/share/x/cover", "https://www.dyframe.art/share/x");

        String block = SharePageController.renderMetaBlock(risky);

        assertThat(block).doesNotContain("onload=\"alert(1)\"");
        assertThat(block).contains("&quot;");
        assertThat(block).contains("&lt;b&gt;");
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
