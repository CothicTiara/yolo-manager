package com.zyl.yolo.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置：跨域 + 本地存储静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${yolo.storage.type:local}")
    private String storageType;

    @Value("${yolo.storage.local-root:./data/storage}")
    private String localRoot;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if ("local".equalsIgnoreCase(storageType)) {
            String absolutePath = Paths.get(localRoot).toAbsolutePath().normalize().toString();
            if (StrUtil.isNotBlank(absolutePath) && !absolutePath.endsWith("/") && !absolutePath.endsWith("\\")) {
                absolutePath = absolutePath + "/";
            }
            registry.addResourceHandler("/storage/**")
                    .addResourceLocations("file:" + absolutePath);
        }
    }
}