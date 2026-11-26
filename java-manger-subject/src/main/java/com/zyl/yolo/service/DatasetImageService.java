package com.zyl.yolo.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.ImageResponse;
import com.zyl.yolo.dto.ImageUploadResponse;
import com.zyl.yolo.entity.Dataset;
import com.zyl.yolo.entity.DatasetImage;
import com.zyl.yolo.mapper.AnnotationMapper;
import com.zyl.yolo.mapper.DatasetImageMapper;
import com.zyl.yolo.storage.BucketUtils;
import com.zyl.yolo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * 数据集图片服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetImageService extends ServiceImpl<DatasetImageMapper, DatasetImage> {

    private static final String[] SUPPORTED_EXT = {"jpg", "jpeg", "png", "bmp", "webp"};

    private final StorageService storageService;
    private final DatasetService datasetService;
    private final DatasetJobRegistry jobRegistry;
    private final AnnotationMapper annotationMapper;

    public ImageUploadResponse uploadImage(Long datasetId, MultipartFile file) {
        Dataset dataset = datasetService.requireDataset(datasetId);
        // 上传期间抢占忙碌锁，阻止并发的删除数据集/导入/导出/标注修改等（上传本身很快，结束即释放）
        jobRegistry.acquire(datasetId, DatasetJobRegistry.Kind.UPLOAD);
        try {
            return doUpload(datasetId, dataset, file);
        } finally {
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.UPLOAD);
        }
    }

    private ImageUploadResponse doUpload(Long datasetId, Dataset dataset, MultipartFile file) {

        String originalName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = StrUtil.subAfter(originalName, ".", true).toLowerCase();
        if (!isSupportedExt(ext)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED,
                    "不支持的图片格式: " + ext + "，支持: jpg/jpeg/png/bmp/webp");
        }

        // 读取尺寸
        int width = 0;
        int height = 0;
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException e) {
            log.warn("读取图片尺寸失败: {}", e.getMessage());
        }

        String fileId = IdUtil.getSnowflakeNextIdStr();
        String key = BucketUtils.join(String.valueOf(datasetId), "images", fileId + "." + ext);
        String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
        try (InputStream inputStream = file.getInputStream()) {
            storageService.upload(BucketUtils.DATASETS, key, inputStream, contentType);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_ERROR, "图片上传失败: " + e.getMessage());
        }

        DatasetImage image = new DatasetImage();
        image.setDatasetId(datasetId);
        image.setFileName(originalName);
        image.setRustfsKey(key);
        image.setWidth(width);
        image.setHeight(height);
        image.setSplit("train");
        save(image);

        // 数据集状态更新
        if ("BUILDING".equals(dataset.getStatus())) {
            datasetService.updateStatus(datasetId, "BUILDING");
        }

        ImageUploadResponse resp = new ImageUploadResponse();
        resp.setFileId(image.getId());
        resp.setFileName(originalName);
        resp.setRustfsKey(key);
        resp.setUrl(storageService.getPublicUrl(BucketUtils.DATASETS, key));
        resp.setWidth(width);
        resp.setHeight(height);
        return resp;
    }

    public java.util.List<ImageResponse> listByDataset(Long datasetId) {
        datasetService.requireDataset(datasetId);
        // 一次聚合查询拿到每图标注数，供前端展示已标注/未标注角标
        java.util.Map<Long, Long> annCounts = annotationMapper.countGroupByImage(datasetId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.zyl.yolo.dto.ImageAnnotationCount::getImageId,
                        com.zyl.yolo.dto.ImageAnnotationCount::getCnt));
        return list(new LambdaQueryWrapper<DatasetImage>()
                        .eq(DatasetImage::getDatasetId, datasetId)
                        .orderByDesc(DatasetImage::getId))
                .stream()
                .map(img -> {
                    ImageResponse resp = ImageResponse.from(img,
                            storageService.getPublicUrl(BucketUtils.DATASETS, img.getRustfsKey()));
                    resp.setAnnotationCount(annCounts.getOrDefault(img.getId(), 0L));
                    return resp;
                })
                // 默认排序：未标注图片在前（便于优先补标），同组内按 id 倒序（新图在前）
                .sorted(java.util.Comparator
                        .comparing((ImageResponse r) -> r.getAnnotationCount() > 0 ? 1 : 0)
                        .thenComparing(ImageResponse::getId, java.util.Comparator.reverseOrder()))
                .collect(java.util.stream.Collectors.toList());
    }

    public DatasetImage requireImage(Long imageId) {
        DatasetImage image = getById(imageId);
        if (image == null) {
            throw new BusinessException(ResultCode.IMAGE_NOT_FOUND);
        }
        return image;
    }

    public void deleteImage(Long datasetId, Long imageId) {
        jobRegistry.assertNotBusy(datasetId);
        DatasetImage image = requireImage(imageId);
        if (!image.getDatasetId().equals(datasetId)) {
            throw new BusinessException(ResultCode.IMAGE_NOT_FOUND);
        }
        storageService.delete(BucketUtils.DATASETS, image.getRustfsKey());
        removeById(imageId);
    }

    public long countByDataset(Long datasetId) {
        return count(new LambdaQueryWrapper<DatasetImage>().eq(DatasetImage::getDatasetId, datasetId));
    }

    private boolean isSupportedExt(String ext) {
        for (String e : SUPPORTED_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }
}