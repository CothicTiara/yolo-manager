package com.zyl.yolo.controller;

import com.zyl.yolo.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 存储文件代理接口：把 S3 / 本地存储中的对象以 HTTP 流方式回吐，
 * 用于让前端通过后端访问私有桶里的图片等资源，避免直连对象存储。
 * 路径：GET /api/storage/{bucket}/{key...}
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageProxyController {

    private final StorageService storageService;

    @GetMapping("/{bucket}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String bucket, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = request.getContextPath() + "/api/storage/" + bucket + "/";
        int idx = uri.indexOf(prefix);
        if (idx < 0) {
            return ResponseEntity.notFound().build();
        }
        String key = uri.substring(idx + prefix.length());
        if (key.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!storageService.exists(bucket, key)) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = storageService.download(bucket, key);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, guessContentType(key))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(content);
    }

    private String guessContentType(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON_VALUE;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN_VALUE;
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
