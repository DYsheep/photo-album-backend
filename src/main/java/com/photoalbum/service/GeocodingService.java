package com.photoalbum.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.entity.GeoCache;
import com.photoalbum.mapper.GeoCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 逆地理编码（GPS → 地名）
 *
 * 数据来源：腾讯位置服务 /ws/geocoder/v1/。
 * 设计要点：
 *  1) **缓存优先**：按"纬度/经度各保留 2 位"（约 1km）做键写入 t_geo_cache，
 *     同一区域只调用一次；接口有配额限制，缓存同时避免超时拖慢海报生成。
 *  2) **可缺席**：未配置 app.geo.key 时直接返回 null，功能整体降级，
 *     不影响年度小结的其它统计。
 *  3) **失败不写死**：接口失败时缓存里 region 为空，下次仍可重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeocodingService {

    private final GeoCacheMapper geoCacheMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.geo.key:}")
    private String apiKey;

    @Value("${app.geo.endpoint:https://apis.map.qq.com/ws/geocoder/v1/}")
    private String endpoint;

    @Value("${app.geo.timeout-ms:2500}")
    private long timeoutMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** 是否已配置 key：未配置时调用方可以跳过整块统计 */
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 坐标键：纬度/经度各两位小数，约 1km 精度 */
    public String coordKey(Double lat, Double lng) {
        if (lat == null || lng == null) return null;
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f", lat, lng);
    }

    /**
     * 解析地名（先查缓存）
     *
     * @return 如 "北京市朝阳区"；无法解析（未启用/失败/坐标缺失）返回 null
     */
    public String resolveRegion(Double lat, Double lng) {
        String key = coordKey(lat, lng);
        if (key == null) return null;

        GeoCache cached = geoCacheMapper.selectOne(
                new QueryWrapper<GeoCache>().eq("coord_key", key).last("LIMIT 1"));
        if (cached != null) {
            return cached.getRegion() == null || cached.getRegion().isBlank() ? null : cached.getRegion();
        }

        if (!enabled()) return null;

        String region = requestRegion(lat, lng);
        saveCache(key, region);
        return region;
    }

    private String requestRegion(Double lat, Double lng) {
        try {
            String location = URLEncoder.encode(lat + "," + lng, StandardCharsets.UTF_8);
            String url = endpoint + "?location=" + location + "&key=" + apiKey + "&get_poi=0";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("逆地理编码 HTTP {}，坐标 {}", response.statusCode(), lat + "," + lng);
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            int status = root.path("status").asInt(-1);
            if (status != 0) {
                log.warn("逆地理编码返回 status={} message={}", status, root.path("message").asText(""));
                return null;
            }
            JsonNode component = root.path("result").path("address_component");
            String city = component.path("city").asText("");
            String district = component.path("district").asText("");
            String province = component.path("province").asText("");
            // 直辖市 city 可能为空，此时用 province；最终拼成"北京市朝阳区"
            String base = city.isBlank() ? province : city;
            if (base.isBlank()) return null;
            return base + district;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("逆地理编码失败: {} 坐标 {}", e.getMessage(), lat + "," + lng);
            return null;
        }
    }

    private void saveCache(String key, String region) {
        try {
            GeoCache cache = new GeoCache();
            cache.setCoordKey(key);
            cache.setRegion(region);
            cache.setApiStatus(region == null ? -1 : 0);
            geoCacheMapper.insert(cache);
        } catch (Exception e) {
            // 并发插入可能撞唯一键，忽略即可（缓存不是关键路径）
            log.debug("写入地理缓存失败: {}", e.getMessage());
        }
    }
}
