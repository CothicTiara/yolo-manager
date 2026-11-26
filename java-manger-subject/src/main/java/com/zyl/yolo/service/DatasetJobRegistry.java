package com.zyl.yolo.service;

import com.zyl.yolo.common.BusinessException;
import com.zyl.yolo.common.ResultCode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据集后台任务互斥登记：以 datasetId 为粒度维护一把"忙碌锁"。
 * <p>导入 / 导出 / 上传等后台或长耗时任务在开始时 {@link #acquire} 抢占，结束时 {@link #release} 释放；
 * 其它会修改数据集的业务（删除数据集、增删图片、标签/标注编辑、重复触发导入导出）在执行前调用
 * {@link #assertNotBusy} 或 {@link #acquire}，若数据集正被占用则抛出 {@link ResultCode#DATASET_BUSY}。</p>
 * <p>注意：本登记仅用于外部接口层面的并发保护，导入/导出后台线程内部对数据的写入不再经过此处。</p>
 */
@Service
public class DatasetJobRegistry {

    /** 后台任务类型 */
    public enum Kind {
        IMPORT("导入"),
        EXPORT("导出"),
        UPLOAD("上传");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final class Entry {
        final Kind kind;
        final long startedAt;

        Entry(Kind kind) {
            this.kind = kind;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final Map<Long, Entry> locks = new ConcurrentHashMap<>();

    /**
     * 原子抢占数据集忙碌锁；若已被占用（无论何种任务）则抛 DATASET_BUSY。
     * 用于导入 / 导出 / 上传的启动，天然阻止重复触发与跨类型并发。
     */
    public void acquire(Long datasetId, Kind kind) {
        Entry prev = locks.putIfAbsent(datasetId, new Entry(kind));
        if (prev != null) {
            throw new BusinessException(ResultCode.DATASET_BUSY,
                    "该数据集正在进行" + prev.kind.label() + "，请等待完成后再" + kind.label());
        }
    }

    /** 释放忙碌锁（仅当当前持有的任务类型匹配时才移除，避免误放） */
    public void release(Long datasetId, Kind kind) {
        Entry cur = locks.get(datasetId);
        if (cur != null && cur.kind == kind) {
            locks.remove(datasetId, cur);
        }
    }

    public boolean isBusy(Long datasetId) {
        return locks.containsKey(datasetId);
    }

    /** 当前占用该数据集的任务类型；未占用返回 null */
    public Kind kindOf(Long datasetId) {
        Entry e = locks.get(datasetId);
        return e == null ? null : e.kind;
    }

    /** 若数据集正被后台任务占用则抛 DATASET_BUSY；用于不抢占锁的修改/删除类操作 */
    public void assertNotBusy(Long datasetId) {
        Entry e = locks.get(datasetId);
        if (e != null) {
            throw new BusinessException(ResultCode.DATASET_BUSY,
                    "该数据集正在进行" + e.kind.label() + "，请等待完成后再操作");
        }
    }
}
