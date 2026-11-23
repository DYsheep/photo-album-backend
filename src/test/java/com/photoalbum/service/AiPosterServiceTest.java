package com.photoalbum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.entity.PosterAi;
import com.photoalbum.mapper.PosterAiMapper;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 年度海报 AI 素材：prompt 组装、能力开关、返回值清洗
 *
 * 不联网：HTTP 调用统一由 AiHttpClient 承担，这里 mock 掉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiPosterService - 海报 AI 素材")
class AiPosterServiceTest {

    @Mock
    private AiHttpClient httpClient;
    @Mock
    private PosterAiMapper posterAiMapper;
    @Mock
    private COSClient cosClient;

    private AiPosterService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ObjectMapper 是具体类，@InjectMocks 无法自动注入，这里手工构造 */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new AiPosterService(httpClient, objectMapper, posterAiMapper, cosClient);
    }

    private Map<String, Object> sampleStats() {
        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("name", "NIKON Z5_2");
        camera.put("count", 72L);
        Map<String, Object> focal = new LinkedHashMap<>();
        focal.put("name", "50mm");
        focal.put("count", 40L);
        Map<String, Object> earliest = new LinkedHashMap<>();
        earliest.put("time", "05:12");
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("time", "23:47");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("year", 2026);
        stats.put("photoCount", 128);
        stats.put("shootingDays", 47);
        stats.put("topCamera", camera);
        stats.put("topFocalLength", focal);
        stats.put("earliest", earliest);
        stats.put("latest", latest);
        return stats;
    }

    @Test
    @DisplayName("背景 prompt 包含年度数据，并明确禁止出现文字")
    void shouldBuildBackgroundPrompt() {
        String prompt = service.buildImagePrompt(2026, sampleStats());

        assertThat(prompt).contains("2026").contains("128").contains("47")
                .contains("NIKON Z5_2").contains("50mm");
        // 关键约束：图像模型容易自己排版文字，必须显式禁止
        assertThat(prompt).contains("禁止出现任何文字");
    }

    @Test
    @DisplayName("未配置图像 key 时不调用图像接口，只生成文案")
    void shouldSkipImageWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(service, "copyApiKey", "copy-key");
        ReflectionTestUtils.setField(service, "copyBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(service, "copyModel", "deepseek-chat");
        ReflectionTestUtils.setField(service, "copyTimeoutMs", 1000L);

        String response = objectMapper.writeValueAsString(Map.of(
                "choices", new Object[]{Map.of("message", Map.of("content", "“清晨与深夜，你都按过快门。”"))}));
        when(httpClient.postJson(eq("https://api.deepseek.com/chat/completions"), eq("copy-key"), anyString(), anyLong()))
                .thenReturn(response);

        Map<String, Object> result = service.generate(2026, sampleStats(), "all");

        // 文案被清洗掉引号与句末标点
        assertThat(result.get("copyText")).isEqualTo("清晨与深夜，你都按过快门");
        assertThat(result.get("backgroundUrl")).isNull();
        verify(cosClient, never()).putObject(any(com.qcloud.cos.model.PutObjectRequest.class));
    }

    @Test
    @DisplayName("接口异常时静默降级，不抛给调用方")
    void shouldDegradeOnFailure() throws Exception {
        ReflectionTestUtils.setField(service, "copyApiKey", "copy-key");
        ReflectionTestUtils.setField(service, "copyBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(service, "copyModel", "deepseek-chat");
        ReflectionTestUtils.setField(service, "copyTimeoutMs", 1000L);
        when(httpClient.postJson(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("HTTP 500"));

        Map<String, Object> result = service.generate(2026, sampleStats(), "all");

        assertThat(result.get("copyText")).isNull();
    }

    @Test
    @DisplayName("能力开关：按配置是否齐全返回")
    void shouldReportCapabilities() {
        assertThat(service.capabilities()).containsEntry("image", false).containsEntry("copy", false);

        ReflectionTestUtils.setField(service, "imageBaseUrl", "https://example.com/v1");
        ReflectionTestUtils.setField(service, "imageApiKey", "k");
        ReflectionTestUtils.setField(service, "imageModel", "img-model");
        assertThat(service.capabilities()).containsEntry("image", true);
    }

    @Test
    @DisplayName("缓存读取：返回已存背景与文案")
    void shouldReturnCached() {
        PosterAi row = new PosterAi();
        row.setYear(2026);
        row.setBackgroundUrl("https://cdn.example.com/poster/ai/year-2026.png");
        row.setCopyText("清晨与深夜");
        when(posterAiMapper.selectOne(any())).thenReturn(row);

        Map<String, Object> cached = service.cached(2026);

        assertThat(cached.get("backgroundUrl")).isEqualTo("https://cdn.example.com/poster/ai/year-2026.png");
        assertThat(cached.get("copyText")).isEqualTo("清晨与深夜");
    }
}
