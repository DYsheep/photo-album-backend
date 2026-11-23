package com.photoalbum.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 极简 HTTP JSON 客户端
 *
 * 抽成独立组件的目的：AI 服务的调用逻辑可测——单测里 mock 掉本类，
 * 不用真的联网，也不用把 HttpClient（final 字段）硬塞进测试。
 */
@Slf4j
@Component
public class AiHttpClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * POST 一段 JSON，返回响应体
     *
     * @param url         完整地址
     * @param bearerToken 为空时不带 Authorization
     * @param jsonBody    请求体
     * @param timeoutMs   读超时（图像生成通常较慢，建议给足）
     */
    public String postJson(String url, String bearerToken, String jsonBody, long timeoutMs) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    /** GET 二进制内容（用于下载图像模型返回的临时 URL） */
    public byte[] getBytes(String url, long timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String abbreviate(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
