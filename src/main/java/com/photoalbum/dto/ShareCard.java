package com.photoalbum.dto;

/**
 * 分享卡片元数据（服务端渲染进 &lt;head&gt; 的 og:* 内容）
 *
 * 背景：社交平台（微信 / QQ / 微博 / Telegram 等）抓取分享链接时只读原始 HTML、
 * 不执行 JavaScript，因此"分享出去显示成一张卡片"这件事无法由前端 SPA 完成，
 * 必须在服务端就写进页面头部。
 *
 * @param title       卡片标题
 * @param description 卡片描述
 * @param imageUrl    卡片封面图（绝对地址，需可被匿名抓取）
 * @param url         卡片指向的页面地址
 */
public record ShareCard(String title, String description, String imageUrl, String url) {
}
