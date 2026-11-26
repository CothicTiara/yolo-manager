package com.zyl.yolo.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ZipUtil;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.ImportProgress;
import com.zyl.yolo.entity.Annotation;
import com.zyl.yolo.entity.DatasetImage;
import com.zyl.yolo.entity.Label;
import com.zyl.yolo.mapper.DatasetImageMapper;
import com.zyl.yolo.storage.BucketUtils;
import com.zyl.yolo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据集导入服务（异步）：支持主流格式快速导入，适配大数据集（数千张图 / 数 GB）。
 * <ul>
 *   <li>COCO JSON：解析 images / annotations / categories，自动创建标签与标注框
 *       （COCO bbox [x,y,w,h] 绝对像素 → YOLO 归一化 [xc,yc,w,h]）</li>
 *   <li>YOLO TXT：图片与同名 .txt 标注一一对应，每行 class_id xc yc w h（已归一化），
 *       类别名优先取 classes.txt / data.yaml names，否则回退 class_{id}；
 *       按 train/val/test 子目录上下文匹配图片与标注，避免不同划分的同名文件相互覆盖</li>
 *   <li>图片压缩包：仅导入图片，不附带标注</li>
 * </ul>
 * 上传后立即返回，后台线程池按数据集并行处理（同一数据集由 DatasetJobRegistry 互斥保证串行），前端通过 getProgress 轮询进度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetImportService {

    private static final String[] IMAGE_EXT = {"jpg", "jpeg", "png", "bmp", "webp"};
    private static final int ANNOTATION_BATCH_SIZE = 500;

    /** YOLO 数据集中不是逐图标注的 txt（类别名清单） */
    private static final Set<String> NON_LABEL_TXT = Set.of(
            "classes.txt", "labelmap.txt", "predefined_classes.txt", "labels.txt");

    /** 常见划分目录名（用于消歧同名文件并写入 dataset_image.split） */
    private static final Set<String> SPLIT_TOKENS = Set.of("train", "val", "valid", "test");

    private final DatasetService datasetService;
    private final DatasetImageMapper datasetImageMapper;
    private final LabelService labelService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final DatasetJobRegistry jobRegistry;

    /** 每个数据集一个导入任务状态；线程池支持不同数据集并行（同一数据集由 DatasetJobRegistry 互斥保证单任务） */
    private final Map<Long, JobState> jobs = new ConcurrentHashMap<>();
    /** 后台导入并行度：同时处理的“不同数据集”导入任务数上限（兼顾吞吐与对存储/数据库的并发压力） */
    private static final int IMPORT_PARALLELISM = 4;
    private final AtomicInteger importThreadSeq = new AtomicInteger();
    private final ExecutorService importExecutor = Executors.newFixedThreadPool(IMPORT_PARALLELISM, r -> {
        Thread t = new Thread(r, "dataset-import-" + importThreadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /* ==================== 对外入口 ==================== */

    /**
     * 启动一次异步导入：先把上传流落盘为稳定临时文件，随后提交后台线程处理并立即返回进度快照。
     *
     * @param datasetId 目标数据集
     * @param archive   zip 压缩包
     * @param format    COCO / YOLO / IMAGES / AUTO（默认 AUTO，按 COCO>YOLO>IMAGES 探测）
     * @param jsonHint  COCO json 在压缩包内的相对路径或文件名（可选）
     */
    public ImportProgress startImport(Long datasetId, MultipartFile archive,
                                      String format, String jsonHint) {
        datasetService.requireDataset(datasetId);
        if (archive == null || archive.isEmpty()) {
            throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED, "上传的压缩包为空");
        }
        String name = archive.getOriginalFilename() == null ? ""
                : archive.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip")) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED, "当前仅支持 .zip 压缩包导入");
        }

        // 抢占数据集忙碌锁：正在进行导入/导出/上传时拒绝重复触发与并发（原子）
        jobRegistry.acquire(datasetId, DatasetJobRegistry.Kind.IMPORT);

        File zip;
        try {
            zip = new File(FileUtil.getTmpDir(),
                    "yolo-import-" + datasetId + "-" + IdUtil.getSnowflakeNextIdStr() + ".zip");
            archive.transferTo(zip);
        } catch (IOException e) {
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.IMPORT);
            throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED, "接收上传文件失败: " + e.getMessage());
        }

        JobState st = new JobState();
        jobs.put(datasetId, st);
        final File zipFile = zip;
        try {
            importExecutor.submit(() -> runImport(datasetId, zipFile, format, jsonHint, st));
        } catch (RuntimeException submitEx) {
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.IMPORT);
            jobs.remove(datasetId);
            throw submitEx;
        }
        return snapshot(datasetId, st);
    }

    public ImportProgress getProgress(Long datasetId) {
        JobState st = jobs.get(datasetId);
        return st == null ? ImportProgress.idle(datasetId) : snapshot(datasetId, st);
    }

    /* ==================== 后台处理 ==================== */

    private void runImport(Long datasetId, File zip, String format, String jsonHint, JobState st) {
        File extractDir = new File(FileUtil.getTmpDir(),
                "yolo-extract-" + datasetId + "-" + IdUtil.getSnowflakeNextIdStr());
        try {
            st.status = "PROCESSING";
            //noinspection ResultOfMethodCallIgnored
            extractDir.mkdirs();
            unzipAutoCharset(zip, extractDir);

            List<File> imageList = collectImageList(extractDir);
            if (imageList.isEmpty()) {
                throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED, "压缩包内未发现任何图片文件");
            }
            Map<String, File> labelBySplitKey = collectYoloLabelFiles(extractDir);
            File cocoJson = locateCocoJson(extractDir, jsonHint);

            String fmt = format == null ? "AUTO" : format.toUpperCase(Locale.ROOT);
            boolean hasYolo = !labelBySplitKey.isEmpty();
            String resolved;
            if ("COCO".equals(fmt) || "YOLO".equals(fmt) || "IMAGES".equals(fmt)) {
                resolved = fmt;
            } else if (cocoJson != null) {
                resolved = "COCO";
            } else if (hasYolo) {
                resolved = "YOLO";
            } else {
                resolved = "IMAGES";
            }
            st.format = resolved;
            st.total.set(imageList.size());

            switch (resolved) {
                case "COCO" -> {
                    if (cocoJson == null) {
                        throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED,
                                "指定 COCO 格式但未找到 annotations/categories 结构的 json");
                    }
                    importCoco(datasetId, cocoJson, buildBasenameMap(imageList), st);
                }
                case "YOLO" -> {
                    if (!hasYolo) {
                        throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED,
                                "指定 YOLO 格式但压缩包内未发现与图片同名的 .txt 标注文件");
                    }
                    importYolo(datasetId, imageList, labelBySplitKey, resolveClassNames(extractDir), st);
                }
                default -> importImages(datasetId, imageList, st);
            }

            st.status = "DONE";
            st.message = String.format("导入完成：图片 %d 张、标注 %d 条、标签 %d 个%s",
                    st.imageCount.get(), st.annCount.get(), st.labelIds.size(),
                    st.skipped.get() == 0 ? "" : ("，跳过 " + st.skipped.get() + " 项"));
            if (st.imageCount.get() > 0) {
                datasetService.updateStatus(datasetId, "BUILDING");
            }
        } catch (Exception e) {
            log.error("数据集导入失败: datasetId={}", datasetId, e);
            st.status = "FAILED";
            st.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            FileUtil.del(extractDir);
            jobRegistry.release(datasetId, DatasetJobRegistry.Kind.IMPORT);
        }
    }

    /* -------------------- COCO -------------------- */
    private void importCoco(Long datasetId, File jsonFile, Map<String, File> imageFiles, JobState st) throws IOException {
        JsonNode root = objectMapper.readTree(jsonFile);
        JsonNode images = root.path("images");
        JsonNode annotations = root.path("annotations");
        JsonNode categories = root.path("categories");
        if (!images.isArray() || images.isEmpty()) {
            throw new BusinessException(ResultCode.DATASET_IMPORT_FAILED, "COCO json 缺少 images 数组");
        }

        Map<String, Label> existingByName = new HashMap<>();
        for (Label l : labelService.listByDataset(datasetId)) {
            if (l.getName() != null) {
                existingByName.putIfAbsent(l.getName().toLowerCase(Locale.ROOT), l);
            }
        }
        List<String> colors = defaultPalette();
        int colorCursor = existingByName.size();
        Map<Long, Long> catToLabel = new HashMap<>();
        if (categories.isArray()) {
            for (JsonNode cat : categories) {
                long catId = cat.path("id").asLong();
                String cname = cat.path("name").asText("class_" + catId);
                Label label = existingByName.get(cname.toLowerCase(Locale.ROOT));
                if (label == null) {
                    label = new Label();
                    label.setName(cname);
                    label.setColor(colors.get(Math.floorMod(colorCursor++, colors.size())));
                    labelService.saveLabel(datasetId, label);
                    existingByName.put(cname.toLowerCase(Locale.ROOT), label);
                }
                catToLabel.put(catId, label.getId());
                st.labelIds.add(label.getId());
            }
        }

        Map<Long, Long> cocoImgToDb = new HashMap<>();
        Map<Long, int[]> imgDims = new HashMap<>();
        for (JsonNode img : images) {
            long cocoId = img.path("id").asLong();
            String fileName = img.path("file_name").asText("");
            File f = imageFiles.get(baseName(fileName).toLowerCase(Locale.ROOT));
            if (f == null) {
                st.skipped.incrementAndGet();
                continue;
            }
            int w = img.path("width").asInt(0);
            int h = img.path("height").asInt(0);
            if (w <= 0 || h <= 0) {
                int[] dims = readDims(f);
                w = dims[0];
                h = dims[1];
            }
            DatasetImage saved = persistImage(datasetId, f, fileName, w, h);
            cocoImgToDb.put(cocoId, saved.getId());
            imgDims.put(cocoId, new int[]{w, h});
            st.imageCount.incrementAndGet();
            st.processed.incrementAndGet();
        }

        List<Annotation> batch = new ArrayList<>();
        if (annotations.isArray()) {
            for (JsonNode ann : annotations) {
                long cocoId = ann.path("image_id").asLong();
                long catId = ann.path("category_id").asLong();
                Long dbImageId = cocoImgToDb.get(cocoId);
                Long labelId = catToLabel.get(catId);
                int[] dims = imgDims.get(cocoId);
                if (dbImageId == null || labelId == null || dims == null || dims[0] <= 0 || dims[1] <= 0) {
                    continue;
                }
                JsonNode bbox = ann.path("bbox");
                if (!bbox.isArray() || bbox.size() < 4) {
                    st.skipped.incrementAndGet();
                    continue;
                }
                double bx = bbox.get(0).asDouble();
                double by = bbox.get(1).asDouble();
                double bw = bbox.get(2).asDouble();
                double bh = bbox.get(3).asDouble();
                if (bw <= 0 || bh <= 0) {
                    continue;
                }
                batch.add(buildAnnotation(dbImageId, labelId,
                        (bx + bw / 2.0) / dims[0], (by + bh / 2.0) / dims[1], bw / dims[0], bh / dims[1]));
                flushIfNeeded(batch, st, false);
            }
        }
        flushIfNeeded(batch, st, true);
    }

    /* -------------------- YOLO TXT -------------------- */
    private void importYolo(Long datasetId, List<File> imageList, Map<String, File> labelBySplitKey,
                            List<String> classNames, JobState st) {
        Map<String, Label> byName = new HashMap<>();
        Map<Integer, Label> byCode = new HashMap<>();
        for (Label l : labelService.listByDataset(datasetId)) {
            if (l.getName() != null) {
                byName.putIfAbsent(l.getName().toLowerCase(Locale.ROOT), l);
            }
            if (l.getCode() != null) {
                byCode.putIfAbsent(l.getCode(), l);
            }
        }
        List<String> palette = defaultPalette();
        int colorCursor = byName.size();
        List<Annotation> batch = new ArrayList<>();

        for (File imgFile : imageList) {
            String fileName = imgFile.getName();
            int[] dims = readDims(imgFile);
            DatasetImage saved = persistImage(datasetId, imgFile, fileName, dims[0], dims[1]);
            st.imageCount.incrementAndGet();
            st.processed.incrementAndGet();

            File txt = labelBySplitKey.get(splitKeyOf(imgFile));
            if (txt == null) {
                continue;
            }
            List<String> lines;
            try {
                lines = FileUtil.readLines(txt, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                st.skipped.incrementAndGet();
                continue;
            }
            for (String raw : lines) {
                String t = raw == null ? "" : raw.replace("\uFEFF", "").trim();
                if (t.isEmpty()) {
                    continue;
                }
                String[] p = t.split("\\s+");
                if (p.length < 5) {
                    st.skipped.incrementAndGet();
                    continue;
                }
                int classId;
                double xc, yc, w, h;
                try {
                    classId = Integer.parseInt(p[0]);
                    xc = Double.parseDouble(p[1]);
                    yc = Double.parseDouble(p[2]);
                    w = Double.parseDouble(p[3]);
                    h = Double.parseDouble(p[4]);
                } catch (NumberFormatException nfe) {
                    st.skipped.incrementAndGet();
                    continue;
                }
                if (classId < 0 || w <= 0 || h <= 0) {
                    continue;
                }
                Label label = byCode.get(classId);
                if (label == null) {
                    String cname = (classNames != null && classId < classNames.size()
                            && classNames.get(classId) != null && !classNames.get(classId).isBlank())
                            ? classNames.get(classId) : ("class_" + classId);
                    label = byName.get(cname.toLowerCase(Locale.ROOT));
                    if (label == null) {
                        label = new Label();
                        label.setName(cname);
                        label.setCode(classId);
                        label.setColor(palette.get(Math.floorMod(colorCursor++, palette.size())));
                        labelService.saveLabel(datasetId, label);
                        byName.put(cname.toLowerCase(Locale.ROOT), label);
                    }
                    byCode.put(classId, label);
                }
                st.labelIds.add(label.getId());
                batch.add(buildAnnotation(saved.getId(), label.getId(), xc, yc, w, h));
                flushIfNeeded(batch, st, false);
            }
        }
        flushIfNeeded(batch, st, true);
    }

    /* -------------------- 纯图片 -------------------- */
    private void importImages(Long datasetId, List<File> imageList, JobState st) {
        for (File f : imageList) {
            int[] dims = readDims(f);
            persistImage(datasetId, f, f.getName(), dims[0], dims[1]);
            st.imageCount.incrementAndGet();
            st.processed.incrementAndGet();
        }
    }

    /* ==================== 工具方法 ==================== */

    /**
     * 解压并自动兼容文件名编码：先按 UTF-8，若因中文 Windows 压缩包（GBK 文件名）
     * 导致 "invalid CEN header (bad entry name or comment)" 等异常，则清空目录后改用 GBK 重试。
     */
    private void unzipAutoCharset(File zip, File out) {
        try {
            ZipUtil.unzip(zip, out, StandardCharsets.UTF_8);
        } catch (Exception first) {
            log.warn("UTF-8 解压失败，回退 GBK 重试: {}", first.getMessage());
            FileUtil.del(out);
            //noinspection ResultOfMethodCallIgnored
            out.mkdirs();
            try {
                ZipUtil.unzip(zip, out, Charset.forName("GBK"));
            } catch (Exception second) {
                log.error("GBK 回退解压仍失败", second);
                throw second;
            }
        }
    }

    private Annotation buildAnnotation(Long imageId, Long labelId, double xc, double yc, double w, double h) {
        Annotation a = new Annotation();
        a.setImageId(imageId);
        a.setLabelId(labelId);
        a.setXCenter(clampNorm(xc));
        a.setYCenter(clampNorm(yc));
        a.setWidth(clampNorm(w));
        a.setHeight(clampNorm(h));
        return a;
    }

    /** 批量写入标注，控制内存；force=true 时清空剩余 */
    private void flushIfNeeded(List<Annotation> batch, JobState st, boolean force) {
        if (batch.isEmpty()) {
            return;
        }
        if (force || batch.size() >= ANNOTATION_BATCH_SIZE) {
            Db.saveBatch(new ArrayList<>(batch), ANNOTATION_BATCH_SIZE);
            st.annCount.addAndGet(batch.size());
            batch.clear();
        }
    }

    private DatasetImage persistImage(Long datasetId, File file, String fileName, int w, int h) {
        String ext = FileUtil.extName(fileName).toLowerCase(Locale.ROOT);
        if (ext.isEmpty() || !isImageExt(ext)) {
            ext = "jpg";
        }
        String fileId = IdUtil.getSnowflakeNextIdStr();
        String key = BucketUtils.join(String.valueOf(datasetId), "images", fileId + "." + ext);
        String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
        byte[] bytes = FileUtil.readBytes(file);
        storageService.upload(BucketUtils.DATASETS, key, bytes, contentType);

        DatasetImage image = new DatasetImage();
        image.setDatasetId(datasetId);
        image.setFileName(fileName);
        image.setRustfsKey(key);
        image.setWidth(w);
        image.setHeight(h);
        String split = splitTokenOf(file.getAbsolutePath().toLowerCase(Locale.ROOT));
        image.setSplit(split.isEmpty() ? "train" : split);
        datasetImageMapper.insert(image);
        return image;
    }

    private List<File> collectImageList(File root) {
        List<File> list = new ArrayList<>();
        for (File f : FileUtil.loopFiles(root)) {
            if (isImageExt(FileUtil.extName(f.getName()).toLowerCase(Locale.ROOT))) {
                list.add(f);
            }
        }
        return list;
    }

    private Map<String, File> buildBasenameMap(List<File> imageList) {
        Map<String, File> map = new HashMap<>();
        for (File f : imageList) {
            map.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), f);
        }
        return map;
    }

    /** 收集 YOLO 标注 txt，按「划分目录 + 去扩展名 basename」索引，消歧 train/val 同名文件 */
    private Map<String, File> collectYoloLabelFiles(File root) {
        Map<String, File> map = new HashMap<>();
        for (File f : FileUtil.loopFiles(root)) {
            String fn = f.getName().toLowerCase(Locale.ROOT);
            if (!fn.endsWith(".txt") || NON_LABEL_TXT.contains(fn)) {
                continue;
            }
            map.putIfAbsent(splitKeyOf(f), f);
        }
        return map;
    }

    /** 计算划分感知的匹配键：split@@baseNoExt（图片与其同名同划分的 txt 得到相同 key） */
    private String splitKeyOf(File f) {
        String pathLower = f.getAbsolutePath().toLowerCase(Locale.ROOT);
        String baseNoExt = FileUtil.mainName(f.getName()).toLowerCase(Locale.ROOT);
        return splitTokenOf(pathLower) + "@@" + baseNoExt;
    }

    /** 取路径中最后出现的划分目录名（valid 归一为 val），无则返回空串；兼容 / 与 \ 两种分隔符 */
    private static String splitTokenOf(String pathLower) {
        String found = "";
        for (String seg : pathLower.split("[\\\\/]")) {
            if (SPLIT_TOKENS.contains(seg)) {
                found = seg;
            }
        }
        return "valid".equals(found) ? "val" : found;
    }

    /** 解析类别名：优先 classes.txt/labelmap.txt（行号即类别 id），其次 data.yaml 的 names */
    private List<String> resolveClassNames(File root) {
        for (File f : FileUtil.loopFiles(root)) {
            String fn = f.getName().toLowerCase(Locale.ROOT);
            if (fn.equals("classes.txt") || fn.equals("labelmap.txt") || fn.equals("predefined_classes.txt")) {
                List<String> names = new ArrayList<>();
                for (String line : FileUtil.readLines(f, StandardCharsets.UTF_8)) {
                    String t = line == null ? "" : line.replace("\uFEFF", "").trim();
                    if (!t.isEmpty()) {
                        names.add(t);
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            }
        }
        for (File f : FileUtil.loopFiles(root)) {
            String fn = f.getName().toLowerCase(Locale.ROOT);
            if (fn.equals("data.yaml") || fn.equals("data.yml")) {
                List<String> names = parseDataYamlNames(f);
                if (names != null && !names.isEmpty()) {
                    return names;
                }
            }
        }
        return null;
    }

    /** 轻量解析 data.yaml 中的 names（支持内联数组 / 列表项 / 数字映射三种写法） */
    private List<String> parseDataYamlNames(File f) {
        try {
            List<String> names = new ArrayList<>();
            boolean inNames = false;
            for (String raw : FileUtil.readLines(f, StandardCharsets.UTF_8)) {
                String line = raw.replace("\uFEFF", "").replace("\t", "  ");
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!inNames) {
                    if (trimmed.startsWith("names:")) {
                        String inline = trimmed.substring("names:".length()).trim();
                        if (inline.startsWith("[") && inline.endsWith("]")) {
                            String inner = inline.substring(1, inline.length() - 1);
                            for (String item : inner.split(",")) {
                                String s = stripQuotes(item.trim());
                                if (!s.isEmpty()) {
                                    names.add(s);
                                }
                            }
                            return names;
                        }
                        inNames = true;
                    }
                } else if (trimmed.startsWith("- ")) {
                    names.add(stripQuotes(trimmed.substring(2).trim()));
                } else if (trimmed.matches("^\\d+\\s*:.*")) {
                    int idx = Integer.parseInt(trimmed.substring(0, trimmed.indexOf(':')).trim());
                    String name = stripQuotes(trimmed.replaceFirst("^\\d+\\s*:\\s*", "").trim());
                    while (names.size() <= idx) {
                        names.add(null);
                    }
                    names.set(idx, name);
                } else {
                    break;
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("解析 data.yaml names 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 在解压目录内寻找 COCO json：优先匹配 hint，其次找含 images+categories 的结构化 json */
    private File locateCocoJson(File root, String jsonHint) {
        List<File> jsons = new ArrayList<>();
        for (File f : FileUtil.loopFiles(root)) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                jsons.add(f);
            }
        }
        if (jsonHint != null && !jsonHint.isBlank()) {
            String hintLower = normalizeHint(jsonHint);
            for (File f : jsons) {
                String rel = relativeTo(f, root).toLowerCase(Locale.ROOT);
                if (rel.equals(hintLower) || rel.endsWith("/" + hintLower)
                        || f.getName().equalsIgnoreCase(jsonHint)) {
                    if (looksLikeCoco(f)) {
                        return f;
                    }
                }
            }
        }
        for (File f : jsons) {
            if (looksLikeCoco(f)) {
                return f;
            }
        }
        return null;
    }

    private boolean looksLikeCoco(File f) {
        try {
            JsonNode root = objectMapper.readTree(f);
            return root.isObject() && root.has("images") && root.path("images").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    /** 仅读图片头获取宽高，避免全量解码，适合数千张图快速导入 */
    private int[] readDims(File f) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            if (iis == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, true, true);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.warn("读取图片尺寸失败 {}: {}", f.getName(), e.getMessage());
        }
        return new int[]{0, 0};
    }

    private boolean isImageExt(String ext) {
        for (String e : IMAGE_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static String normalizeHint(String hint) {
        return hint.replace('\\', '/').replaceAll("^/+", "").toLowerCase(Locale.ROOT);
    }

    private String relativeTo(File f, File root) {
        String rp = root.getAbsolutePath();
        String fp = f.getAbsolutePath();
        if (fp.startsWith(rp)) {
            String rel = fp.substring(rp.length());
            while (rel.startsWith(File.separator) || rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            return rel.replace('\\', '/');
        }
        return f.getName();
    }

    private static BigDecimal clampNorm(double v) {
        double x = Math.max(0.0, Math.min(1.0, v));
        return BigDecimal.valueOf(x).setScale(6, RoundingMode.HALF_UP);
    }

    private List<String> defaultPalette() {
        return List.of("#409EFF", "#67C23A", "#E6A23C", "#F56C6C", "#909399",
                "#9C27B0", "#00BCD4", "#FF9800", "#4CAF50", "#E91E63");
    }

    private ImportProgress snapshot(Long datasetId, JobState st) {
        ImportProgress p = new ImportProgress();
        p.setDatasetId(datasetId);
        p.setStatus(st.status);
        p.setFormat(st.format);
        p.setTotalImages(st.total.get());
        p.setProcessedImages(st.processed.get());
        p.setImageCount(st.imageCount.get());
        p.setAnnotationCount(st.annCount.get());
        p.setLabelCount(st.labelIds.size());
        p.setSkippedCount(st.skipped.get());
        p.setMessage(st.message);
        p.setError(st.error);
        return p;
    }

    /** 单个导入任务的内部可变状态 */
    private static class JobState {
        volatile String status = "PENDING";
        volatile String format;
        volatile String message;
        volatile String error;
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger imageCount = new AtomicInteger();
        final AtomicInteger annCount = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final Set<Long> labelIds = ConcurrentHashMap.newKeySet();
    }
}
