package com.zyl.yolo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import com.zyl.yolo.dto.BaseModelInfo;
import com.zyl.yolo.dto.OfficialBaseModel;
import com.zyl.yolo.entity.BaseModel;
import com.zyl.yolo.mapper.BaseModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基础模型目录服务：
 * <ul>
 *   <li>训练表单下拉与创建校验改为从 DB base_model 表读取；</li>
 *   <li>刷新已下载状态：调用 Python .124 /api/base-models/local 扫描 base_models_dir，按文件名匹配回写 status/size；</li>
 *   <li>同步官网目录：调用 Python /api/base-models/official 拉取 Ultralytics GitHub Release 检测权重列表并 upsert；</li>
 *   <li>触发下载：调用 Python /api/base-models/download，DB 置 DOWNLOADING，实际文件由 Python 后台线程写入 base_models_dir。</li>
 * </ul>
 *
 * <p>下载状态转移：Java 侧不常驻轮询；前端在存在 DOWNLOADING 行时定时回调 {@link #refreshStatusFromTrainService()}
 * 完成状态收敛。Python 若重启导致 .part 未清理，超时后仍可再次触发下载覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseModelService extends ServiceImpl<BaseModelMapper, BaseModel> {

    private final RestTemplate restTemplate;

    @Value("${yolo.train-service.base-url:http://localhost:8000}")
    private String trainServiceBaseUrl;

    // ------------------------------ 查询 ------------------------------

    /** 全量目录（前端管理页 & 内部下拉） */
    public List<BaseModelInfo> listAll() {
        return list(new LambdaQueryWrapper<BaseModel>().orderByAsc(BaseModel::getFamily).orderByAsc(BaseModel::getScale))
                .stream().map(this::toInfo).collect(Collectors.toList());
    }

    /** 训练表单可选：enabled=1 且状态为 DOWNLOADED 的 modelName 列表 */
    public List<String> listEnabledDownloadedNames() {
        return list(new LambdaQueryWrapper<BaseModel>()
                .eq(BaseModel::getEnabled, 1)
                .eq(BaseModel::getStatus, BaseModel.STATUS_DOWNLOADED)
                .orderByAsc(BaseModel::getFamily).orderByAsc(BaseModel::getScale))
                .stream().map(BaseModel::getModelName).collect(Collectors.toList());
    }

    /** 判断 modelName 是否在 DB 中且启用（训练提交校验用；不校验是否已下载，交给 Python 加载时报错） */
    public boolean isSupported(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        return count(new LambdaQueryWrapper<BaseModel>()
                .eq(BaseModel::getModelName, modelName)
                .eq(BaseModel::getEnabled, 1)) > 0;
    }

    // ------------------------------ 状态刷新 ------------------------------

    /**
     * 从 Python 训练端拉取本地权重清单，回写 status/sizeBytes/localPath/lastSyncAt。
     * 匹配规则：filename 等于 official_file 或 {modelName}.pt（兼容以前按平台名重命名落盘的旧权重）。
     */
    public List<BaseModelInfo> refreshStatusFromTrainService() {
        Map<String, Object> resp = exchange("/api/base-models/local", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});
        String dir = resp == null ? null : (String) resp.get("dir");
        List<Map<String, Object>> files = listOf(resp, "files");
        List<Map<String, Object>> parts = listOf(resp, "parts");
        List<Map<String, Object>> failed = listOf(resp, "failed");

        // 建 filename -> size 索引，供下面按候选文件名 O(1) 命中
        Map<String, Long> fileIndex = new HashMap<>();
        for (Map<String, Object> f : files) {
            String name = str(f.get("filename"));
            long size = num(f.get("size_bytes")).longValue();
            if (name != null) fileIndex.put(name, size);
        }
        Set<String> partIndex = parts.stream().map(m -> str(m.get("filename")))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> failIndex = new HashMap<>();
        for (Map<String, Object> fm : failed) {
            String name = str(fm.get("filename"));
            String err = str(fm.get("error"));
            if (name != null) failIndex.put(name, err);
        }

        LocalDateTime now = LocalDateTime.now();
        List<BaseModel> all = list();
        for (BaseModel bm : all) {
            String platformFile = bm.getModelName() + ".pt";
            String officialFile = bm.getOfficialFile();
            String hit = null;
            if (officialFile != null && fileIndex.containsKey(officialFile)) hit = officialFile;
            else if (fileIndex.containsKey(platformFile)) hit = platformFile;

            if (hit != null) {
                bm.setStatus(BaseModel.STATUS_DOWNLOADED);
                bm.setSizeBytes(fileIndex.get(hit));
                bm.setErrorMsg(null);
                if (dir != null) bm.setLocalPath(dir + "/" + hit);
            } else {
                String partFile = platformFile + ".part";
                String officialPart = officialFile == null ? null : officialFile + ".part";
                String failFile = platformFile + ".failed";
                String officialFail = officialFile == null ? null : officialFile + ".failed";
                if (partIndex.contains(partFile) || (officialPart != null && partIndex.contains(officialPart))) {
                    bm.setStatus(BaseModel.STATUS_DOWNLOADING);
                } else if (failIndex.containsKey(failFile)) {
                    bm.setStatus(BaseModel.STATUS_FAILED);
                    bm.setErrorMsg(failIndex.get(failFile));
                } else if (officialFail != null && failIndex.containsKey(officialFail)) {
                    bm.setStatus(BaseModel.STATUS_FAILED);
                    bm.setErrorMsg(failIndex.get(officialFail));
                } else if (!BaseModel.STATUS_DOWNLOADING.equals(bm.getStatus())) {
                    // 未在本地/在下载/未失败：视为缺失；但保留刚触发的 DOWNLOADING，避免刷新与 Python 起线程之间的时序竞态
                    bm.setStatus(BaseModel.STATUS_MISSING);
                    bm.setSizeBytes(0L);
                }
            }
            bm.setLastSyncAt(now);
        }
        if (!all.isEmpty()) updateBatchById(all);
        return all.stream().map(this::toInfo).collect(Collectors.toList());
    }

    // ------------------------------ 官网同步 ------------------------------

    /**
     * 从 Ultralytics 官方 GitHub Release 拉取当前支持的基础模型目录，返回给前端做「加入/下载」勾选。
     * 同时 upsert 到 base_model 表：
     *   - 已存在（同 modelName）：仅更新 downloadUrl/officialFile/family/scale，保留 status 与 sizeBytes；
     *   - 不存在：作为 MISSING/enabled=1 新增。
     */
    public List<OfficialBaseModel> syncFromOfficial() {
        Map<String, Object> resp = exchange("/api/base-models/official", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> items = listOf(resp, "items");
        String tag = resp == null ? null : str(resp.get("tag"));
        if (items.isEmpty()) return Collections.emptyList();

        // 预载 DB 中现有 modelName -> row
        Map<String, BaseModel> existing = list().stream()
                .collect(Collectors.toMap(BaseModel::getModelName, b -> b, (a, b) -> a));

        List<OfficialBaseModel> out = new ArrayList<>();
        List<BaseModel> toInsert = new ArrayList<>();
        List<BaseModel> toUpdate = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String modelName = str(it.get("model_name"));
            String officialFile = str(it.get("official_file"));
            String family = str(it.get("family"));
            String scale = str(it.get("scale"));
            String url = str(it.get("download_url"));
            if (modelName == null || officialFile == null) continue;

            BaseModel bm = existing.get(modelName);
            if (bm == null) {
                bm = new BaseModel();
                bm.setModelName(modelName);
                bm.setFamily(family);
                bm.setScale(scale);
                bm.setOfficialFile(officialFile);
                bm.setDownloadUrl(url);
                bm.setStatus(BaseModel.STATUS_MISSING);
                bm.setSizeBytes(0L);
                bm.setEnabled(1);
                toInsert.add(bm);
            } else {
                bm.setFamily(family);
                bm.setScale(scale);
                bm.setOfficialFile(officialFile);
                bm.setDownloadUrl(url);
                toUpdate.add(bm);
            }

            OfficialBaseModel o = new OfficialBaseModel();
            o.setModelName(modelName);
            o.setFamily(family);
            o.setScale(scale);
            o.setOfficialFile(officialFile);
            o.setDownloadUrl(url);
            o.setKnown(existing.containsKey(modelName));
            o.setCurrentStatus(o.getKnown() ? existing.get(modelName).getStatus() : null);
            out.add(o);
        }
        if (!toInsert.isEmpty()) saveBatch(toInsert);
        if (!toUpdate.isEmpty()) updateBatchById(toUpdate);
        log.info("同步官网基础模型目录完成：tag={} 条目={} 新增={} 更新={}",
                tag, out.size(), toInsert.size(), toUpdate.size());
        return out;
    }

    // ------------------------------ 触发下载 ------------------------------

    /**
     * 触发 Python 端后台下载指定 modelName 集合（必须已存在于 base_model 表）。
     * 调用成功即把 DB 状态置为 DOWNLOADING；实际完成/失败由下一次 refreshStatus 回写。
     */
    public List<BaseModelInfo> requestDownload(List<String> modelNames) {
        if (modelNames == null || modelNames.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请至少选择一个基础模型");
        }
        List<BaseModel> rows = list(new LambdaQueryWrapper<BaseModel>().in(BaseModel::getModelName, modelNames));
        Set<String> foundNames = rows.stream().map(BaseModel::getModelName).collect(Collectors.toSet());
        for (String n : modelNames) {
            if (!foundNames.contains(n)) {
                throw new BusinessException(ResultCode.BASE_MODEL_NOT_FOUND, "模型未同步：请先「同步官网最新」再下载 " + n);
            }
        }

        List<Map<String, String>> items = new ArrayList<>();
        for (BaseModel bm : rows) {
            Map<String, String> item = new HashMap<>();
            item.put("model_name", bm.getModelName());
            item.put("official_file", bm.getOfficialFile());
            item.put("download_url", bm.getDownloadUrl());
            items.add(item);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);

        Map<String, Object> resp;
        try {
            resp = exchange("/api/base-models/download", HttpMethod.POST, body,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("调用 Python 下载接口失败: {}", e.getMessage());
            for (BaseModel bm : rows) {
                bm.setStatus(BaseModel.STATUS_FAILED);
                bm.setErrorMsg("训练服务不可达: " + e.getMessage());
            }
            updateBatchById(rows);
            throw new BusinessException(ResultCode.BASE_MODEL_DOWNLOAD_FAILED,
                    "触发下载失败：" + e.getMessage());
        }

        // Python 已接收任务，标记 DOWNLOADING 并清空 errorMsg
        for (BaseModel bm : rows) {
            bm.setStatus(BaseModel.STATUS_DOWNLOADING);
            bm.setErrorMsg(null);
        }
        updateBatchById(rows);
        log.info("基础模型下载已提交：count={} resp={}", rows.size(), resp);
        return rows.stream().map(this::toInfo).collect(Collectors.toList());
    }

    /** 删除目录条目（仅允许非 DOWNLOADED 状态；文件不删除，避免影响正在运行的训练） */
    public void removeModel(Long id) {
        BaseModel bm = getById(id);
        if (bm == null) throw new BusinessException(ResultCode.BASE_MODEL_NOT_FOUND);
        if (BaseModel.STATUS_DOWNLOADED.equals(bm.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "已下载的权重请先在服务器上手动清理后再删除目录条目");
        }
        removeById(id);
    }

    /** 启用/下架切换 */
    public BaseModelInfo toggleEnabled(Long id, boolean enabled) {
        BaseModel bm = getById(id);
        if (bm == null) throw new BusinessException(ResultCode.BASE_MODEL_NOT_FOUND);
        bm.setEnabled(enabled ? 1 : 0);
        updateById(bm);
        return toInfo(bm);
    }

    /**
     * 从存储 key 推断基础模型名（在路径中包含 yolov8n 等字样时），供续训任务识别来源 base。
     * 找不到时返回 null。返回首个命中项。
     */
    public String inferModelBaseFromKey(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase();
        List<BaseModel> all = list();
        // 名字长的优先（yolov11n 优先于 yolov8n）
        all.sort((a, b) -> Integer.compare(b.getModelName().length(), a.getModelName().length()));
        for (BaseModel m : all) {
            if (lower.contains(m.getModelName().toLowerCase())) return m.getModelName();
        }
        return null;
    }

    // ------------------------------ 内部工具 ------------------------------

    private <T> T exchange(String path, HttpMethod method, Object body,
                           ParameterizedTypeReference<T> type) {
        String url = trainServiceBaseUrl + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url, method, entity, type).getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> resp, String key) {
        if (resp == null) return Collections.emptyList();
        Object v = resp.get(key);
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>(l.size());
            for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        return Collections.emptyList();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Number num(Object o) {
        if (o instanceof Number n) return n;
        if (o == null) return 0;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private BaseModelInfo toInfo(BaseModel bm) {
        BaseModelInfo info = new BaseModelInfo();
        info.setId(bm.getId());
        info.setModelName(bm.getModelName());
        info.setFamily(bm.getFamily());
        info.setScale(bm.getScale());
        info.setOfficialFile(bm.getOfficialFile());
        info.setDownloadUrl(bm.getDownloadUrl());
        info.setSizeBytes(bm.getSizeBytes());
        info.setStatus(bm.getStatus());
        info.setErrorMsg(bm.getErrorMsg());
        info.setEnabled(bm.getEnabled() != null && bm.getEnabled() == 1);
        info.setLocalPath(bm.getLocalPath());
        info.setLastSyncAt(bm.getLastSyncAt());
        info.setCreatedAt(bm.getCreatedAt());
        info.setUpdatedAt(bm.getUpdatedAt());
        return info;
    }

    /** 供启动时校验：至少有一条 DOWNLOADED 记录，避免用户看不到任何基础模型 */
    public boolean hasAnyDownloaded() {
        return count(new LambdaQueryWrapper<BaseModel>().eq(BaseModel::getStatus, BaseModel.STATUS_DOWNLOADED)) > 0;
    }

    /** 收集所有已启用模型名集合（校验用户下拉是否合法时用） */
    public Set<String> enabledNameSet() {
        return new HashSet<>(list(new LambdaQueryWrapper<BaseModel>().eq(BaseModel::getEnabled, 1))
                .stream().map(BaseModel::getModelName).collect(Collectors.toList()));
    }
}
