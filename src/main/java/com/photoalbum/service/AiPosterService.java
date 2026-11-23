package com.photoalbum.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.entity.PosterAi;
import com.photoalbum.mapper.PosterAiMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 年度海报的 AI 素材（A + C 方案）
 *
 * A：AI 生成海报**背景**——文字与数字仍由前端 Canvas 精确绘制，
 *    因为图像模型对中文和数字几乎不可靠；背景一年生成一次并缓存复用。
 * C：DeepSeek 生成**一句年度文案**（纯文本，不存在渲染问题）。
 *
 * 设计约束：
 *  1) 两类能力都可缺席：未配置 key 时对应部分直接跳过，不影响海报其它内容；
 *  2) 结果全部落库（t_poster_ai），同一年的背景不会重复计费；
 *  3) 图像接口按 OpenAI 兼容格式调用（{base}{path}），
 *     国内多数厂商（火山方舟 / 阿里百炼兼容模式 / 硅基流动 / 智谱）都支持该形状。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPosterService {

    private final AiHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PosterAiMapper posterAiMapper;
    private final COSClient cosClient;

    @Value("${cos.bucket}")
    private String cosBucket;

    @Value("${cos.domain}")
    private String cosDomain;

    @Value("${app.ai.image.base-url:}")
    private String imageBaseUrl;

    @Value("${app.ai.image.path:/images/generations}")
    private String imagePath;

    @Value("${app.ai.image.api-key:}")
    private String imageApiKey;

    @Value("${app.ai.image.model:}")
    private String imageModel;

    @Value("${app.ai.image.size:1024x1536}")
    private String imageSize;

    @Value("${app.ai.image.timeout-ms:120000}")
    private long imageTimeoutMs;

    @Value("${app.ai.copy.base-url:https://api.deepseek.com}")
    private String copyBaseUrl;

    @Value("${app.ai.copy.api-key:}")
    private String copyApiKey;

    @Value("${app.ai.copy.model:deepseek-chat}")
    private String copyModel;

    @Value("${app.ai.copy.timeout-ms:30000}")
    private long copyTimeoutMs;

    public boolean imageEnabled() {
        return notBlank(imageBaseUrl) && notBlank(imageApiKey) && notBlank(imageModel);
    }

    public boolean copyEnabled() {
        return notBlank(copyApiKey);
    }

    /** 前端据此决定是否显示"AI 背景 / AI 文案"按钮 */
    public Map<String, Object> capabilities() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("image", imageEnabled());
        cap.put("copy", copyEnabled());
        return cap;
    }

    /** 读取该年已缓存的素材（不触发生成） */
    public Map<String, Object> cached(Integer year) {
        PosterAi row = find(year);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("year", year);
        data.put("capabilities", capabilities());
        data.put("backgroundUrl", row == null ? null : row.getBackgroundUrl());
        data.put("backgroundPrompt", row == null ? null : row.getBackgroundPrompt());
        data.put("copyText", row == null ? null : row.getCopyText());
        return data;
    }

    /**
     * 生成（或补齐）该年的 AI 素材
     *
     * @param stats 已算好的年度统计，用于拼 prompt 与文案
     * @param part  background / copy / all
     */
    public Map<String, Object> generate(Integer year, Map<String, Object> stats, String part) {
        PosterAi row = find(year);
        if (row == null) {
            row = new PosterAi();
            row.setYear(year);
        }
        boolean wantBackground = ("background".equalsIgnoreCase(part) || "all".equalsIgnoreCase(part));
        boolean wantCopy = ("copy".equalsIgnoreCase(part) || "all".equalsIgnoreCase(part));

        if (wantBackground && imageEnabled()) {
            try {
                String prompt = buildImagePrompt(year, stats);
                byte[] png = requestBackground(prompt);
                row.setBackgroundUrl(uploadBackground(year, png));
                row.setBackgroundPrompt(prompt);
                row.setImageModel(imageModel);
            } catch (Exception e) {
                log.warn("AI 背景生成失败: {}", e.getMessage());
            }
        }

        if (wantCopy && copyEnabled()) {
            try {
                row.setCopyText(requestCopy(year, stats));
            } catch (Exception e) {
                log.warn("AI 文案生成失败: {}", e.getMessage());
            }
        }

        save(row);

        // 直接用内存中的结果构造响应：不依赖"回读数据库"，
        // 即使落库失败（例如缓存表尚未迁移），前端也能拿到本次生成的内容。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("year", year);
        data.put("capabilities", capabilities());
        data.put("backgroundUrl", row.getBackgroundUrl());
        data.put("backgroundPrompt", row.getBackgroundPrompt());
        data.put("copyText", row.getCopyText());
        return data;
    }

    // ============================================================
    // 背景
    // ============================================================

    /**
     * 固定模板 + 年度数据
     *
     * 关键约束写在 prompt 里：**不要出现任何文字/数字/水印**，
     * 并明确要求为排版预留深色留白——否则模型很容易自己"排"一版字。
     */
    String buildImagePrompt(Integer year, Map<String, Object> stats) {
        String focal = firstOf(stats, "topFocalLength");
        String camera = firstOf(stats, "topCamera");
        Object count = stats.get("photoCount");
        Object days = stats.get("shootingDays");
        return "极简高级风格的年度摄影回顾海报背景。深海军蓝到近黑的垂直渐变，"
                + "右上角有微弱的冷蓝色光晕，中下部有柔和的抽象光斑与细腻胶片颗粒质感，"
                + "构图干净、留白充足，适合在其上叠加文字排版。"
                + "画面中禁止出现任何文字、字母、数字、印章、水印或边框。"
                + "整体气质参考：" + (camera.isEmpty() ? "专业摄影" : camera) + " 与 " + (focal.isEmpty() ? "标准镜头" : focal) + "。"
                + "年度元素：" + year + " 年，约 " + (count == null ? "若干" : count) + " 张作品，"
                + (days == null ? "多次" : days) + " 天拍摄。";
    }

    private byte[] requestBackground(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", imageModel);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", imageSize);
        // OpenAI 兼容厂商普遍支持该字段；返回 b64 可省去二次下载
        body.put("response_format", "b64_json");

        String url = trimTrailingSlash(imageBaseUrl) + imagePath;
        String response = httpClient.postJson(url, imageApiKey,
                objectMapper.writeValueAsString(body), imageTimeoutMs);

        JsonNode root = objectMapper.readTree(response);
        JsonNode first = root.path("data").path(0);
        String b64 = first.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }
        String tempUrl = first.path("url").asText("");
        if (!tempUrl.isBlank()) {
            return httpClient.getBytes(tempUrl, imageTimeoutMs);
        }
        throw new IllegalStateException("图像接口未返回图片数据: " + abbreviate(response));
    }

    private String uploadBackground(Integer year, byte[] png) {
        String key = "poster/ai/year-" + year + "-" + System.currentTimeMillis() + ".png";
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(png.length);
        meta.setContentType("image/png");
        cosClient.putObject(new PutObjectRequest(cosBucket, key,
                new ByteArrayInputStream(png), meta));
        return cosDomain + "/" + key;
    }

    // ============================================================
    // 文案
    // ============================================================

    String requestCopy(Integer year, Map<String, Object> stats) throws Exception {
        String facts = describeStats(year, stats);
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "你是一位摄影杂志编辑。根据给定的年度拍摄数据，写一句中文年度总结："
                + "不超过 20 个汉字，不使用引号、书名号和表情符号，句末不加标点，只输出这句话本身。");

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", facts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", copyModel);
        body.put("messages", new Object[]{system, user});
        body.put("temperature", 1.0);
        body.put("max_tokens", 80);

        String url = trimTrailingSlash(copyBaseUrl) + "/chat/completions";
        String response = httpClient.postJson(url, copyApiKey,
                objectMapper.writeValueAsString(body), copyTimeoutMs);

        JsonNode root = objectMapper.readTree(response);
        String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
        // 兜底清洗：去掉模型仍可能带上的引号与首尾标点
        text = text.replaceAll("^[\"'“”‘’《》]+|[\"'“”‘’《》]+$", "").replaceAll("[。．.!！?？]+$", "");
        if (text.isBlank()) return null;
        return text.length() > 30 ? text.substring(0, 30) : text;
    }

    private String describeStats(Integer year, Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append(year).append(" 年：拍摄 ").append(stats.getOrDefault("photoCount", 0)).append(" 张，");
        sb.append(stats.getOrDefault("shootingDays", 0)).append(" 天有拍摄");
        Map<String, Object> earliest = asMap(stats.get("earliest"));
        Map<String, Object> latest = asMap(stats.get("latest"));
        if (earliest != null && earliest.get("time") != null) {
            sb.append("，最早一次在 ").append(earliest.get("time"));
        }
        if (latest != null && latest.get("time") != null) {
            sb.append("，最晚一次在 ").append(latest.get("time"));
        }
        String camera = firstOf(stats, "topCamera");
        String focal = firstOf(stats, "topFocalLength");
        if (!camera.isEmpty()) sb.append("，最常用设备 ").append(camera);
        if (!focal.isEmpty()) sb.append("，最常用焦段 ").append(focal);
        sb.append("。");
        return sb.toString();
    }

    // ============================================================
    // 持久化与工具
    // ============================================================

    private PosterAi find(Integer year) {
        if (year == null) return null;
        return posterAiMapper.selectOne(new QueryWrapper<PosterAi>().eq("year", year).last("LIMIT 1"));
    }

    private void save(PosterAi row) {
        try {
            if (row.getId() == null) {
                posterAiMapper.insert(row);
            } else {
                posterAiMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("保存海报 AI 素材失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** 取 topCamera / topFocalLength 这类结构里的 name */
    private String firstOf(Map<String, Object> stats, String key) {
        Map<String, Object> map = asMap(stats.get(key));
        if (map == null || map.get("name") == null) return "";
        return String.valueOf(map.get("name"));
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
