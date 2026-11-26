package com.zyl.yolo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 文档配置：访问 /swagger-ui.html 查看在线接口文档
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI yoloManagerOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("YOLO 视觉识别模型管理平台 API")
                .description("数据集管理、标注、训练、模型版本与对外推理识别接口文档")
                .version("1.0.0"));
    }
}