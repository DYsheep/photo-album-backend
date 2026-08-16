package com.photoalbum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * - 静态资源映射：将上传目录映射为 /files/** 访问路径
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 文件上传目录 → /files/** 路径访问
        // 例如: http://localhost:8080/files/2026/05/xxx.jpg
        // 使用 file:/// 前缀（RFC 8089），Windows 上 "file:C:/..." 格式不可靠
        String location = "file:///" + uploadDir;
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location);
    }
}
