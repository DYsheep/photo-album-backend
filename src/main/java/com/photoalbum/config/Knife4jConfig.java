package com.photoalbum.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / SpringDoc 接口文档配置
 * 访问地址: /doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("摄影相册 API 文档")
                        .version("1.0.0")
                        .description("Photo Album 后端接口文档")
                        .contact(new Contact().name("DYSheep")));
    }
}
