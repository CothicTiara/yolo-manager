package com.zyl.yolo.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.InferenceResponse;
import com.zyl.yolo.entity.InferenceTest;
import com.zyl.yolo.entity.ModelVersion;
import com.zyl.yolo.mapper.InferenceTestMapper;
import com.zyl.yolo.storage.BucketUtils;
import com.zyl.yolo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 推理测试服务：上传测试图片，调用 FastAPI 推理，保存测试记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final ModelVersionService modelVersionService;
    private final InferenceTestMapper inferenceTestMapper;

    @Value("${yolo.train-service.base-url:http://localhost:8000}")
    private String trainServiceBaseUrl;

    /**
     * 执行模型调用测试
     */
    public InferenceResponse testModel(Long modelVersionId, MultipartFile file) {
        ModelVersion mv = modelVersionService.require(modelVersionId);

        // 上传测试图片到 yolo-tests/{uuid}/input.jpg
        String testId = IdUtil.getSnowflakeNextIdStr();
        String originalName = file.getOriginalFilename() == null ? "input.jpg" : file.getOriginalFilename();
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "jpg";
        if (!isImage(ext)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED, "请上传图片文件");
        }
        String imageKey = BucketUtils.join(testId, "input." + ext);
        try {
            storageService.upload(BucketUtils.TESTS, imageKey, file.getInputStream(), "image/" + ext);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "测试图片上传失败: " + e.getMessage());
        }

        // 调用 FastAPI /api/inference/predict
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("modelVersionId", String.valueOf(modelVersionId));
            form.add("imageKey", imageKey);
            form.add("bucket", BucketUtils.TESTS);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(form, headers);

            String json = restTemplate.postForObject(
                    trainServiceBaseUrl + "/api/inference/predict", entity, String.class);
            JsonNode root = objectMapper.readTree(json == null ? "{}" : json);
            JsonNode data = root.has("data") ? root.get("data") : root;

            InferenceResponse resp = new InferenceResponse();
            resp.setInferenceTimeMs(data.has("inferenceTimeMs") ? data.get("inferenceTimeMs").asInt() : 0);

            List<InferenceResponse.Detection> detections = new ArrayList<>();
            if (data.has("detections") && data.get("detections").isArray()) {
                for (JsonNode node : data.get("detections")) {
                    InferenceResponse.Detection d = new InferenceResponse.Detection();
                    d.setLabel(node.path("label").asText());
                    if (node.has("labelId")) {
                        d.setLabelId(node.get("labelId").asInt());
                    }
                    if (node.has("confidence")) {
                        d.setConfidence(node.get("confidence").asDouble());
                    }
                    if (node.has("x1")) {
                        d.setX1(node.get("x1").asDouble());
                        d.setY1(node.get("y1").asDouble());
                        d.setX2(node.get("x2").asDouble());
                        d.setY2(node.get("y2").asDouble());
                    }
                    detections.add(d);
                }
            }
            resp.setDetections(detections);

            // 服务端标注图与原图尺寸（Python 推理服务新增字段）
            if (data.hasNonNull("annotatedImageBase64")) {
                resp.setAnnotatedImageBase64(data.get("annotatedImageBase64").asText());
            }
            if (data.hasNonNull("annotatedImageKey")) {
                resp.setAnnotatedImageUrl(storageService.getPublicUrl(BucketUtils.TESTS,
                        data.get("annotatedImageKey").asText()));
            }
            if (data.has("imageWidth")) {
                resp.setImageWidth(data.get("imageWidth").asInt());
            }
            if (data.has("imageHeight")) {
                resp.setImageHeight(data.get("imageHeight").asInt());
            }
            resp.setModelUuid(mv.getUuid());
            resp.setModelVersion(mv.getVersion());

            // 保存测试记录
            modelVersionService.saveInferenceTest(modelVersionId, imageKey,
                    objectMapper.writeValueAsString(resp), resp.getInferenceTimeMs());
            return resp;
        } catch (Exception e) {
            log.warn("调用 FastAPI 推理失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.TRAIN_SERVICE_ERROR, "推理服务调用失败: " + e.getMessage());
        }
    }

    public List<InferenceTest> listTests(Long modelVersionId) {
        return inferenceTestMapper.selectList(new LambdaQueryWrapper<InferenceTest>()
                .eq(InferenceTest::getModelVersionId, modelVersionId)
                .orderByDesc(InferenceTest::getCreatedAt));
    }

    private boolean isImage(String ext) {
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "bmp".equals(ext) || "webp".equals(ext);
    }
}