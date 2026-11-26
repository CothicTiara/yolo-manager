package com.zyl.yolo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * YOLO 视觉识别模型管理平台 - 管理后端启动类
 */
@SpringBootApplication
@MapperScan("com.zyl.yolo.mapper")
public class YoloManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoloManagerApplication.class, args);
    }
}