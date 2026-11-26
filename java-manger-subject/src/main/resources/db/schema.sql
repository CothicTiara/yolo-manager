-- =============================================================================
-- YOLO 视觉识别模型管理平台 —— 数据库结构（MySQL 8.0+，字符集 utf8mb4）
--
-- 来源：从现网库 yolo_manager（MySQL 8.4.13）用 SHOW CREATE TABLE 导出后整理，
--       并与 java-manger-subject 的 entity/*.java 字段逐一核对。
-- 整理时做了两处修正：
--   1. 原库中 8 张表的表/列注释在库里被存成了 "?" 字节（早期用非 utf8 连接建表所致），
--      本文件按实体语义重写为中文注释；base_model 表原本就是正确的。
--   2. 去掉了导出时的 AUTO_INCREMENT 起始值，改为 IF NOT EXISTS 以便重复执行。
--
-- 执行方式（任选其一）：
--   mysql -h <host> -P <port> -u <user> -p < db/schema.sql
--   ./deploy.sh 或 .\deploy.ps1 会在部署前自动执行本文件（幂等，已存在的表不会被改动）
--
-- 说明：
--   * MyBatis-Plus 全局配置了 logic-delete-field: deleted，但所有实体都没有该属性，
--     因此表结构中不存在 deleted 列，删除均为物理删除 —— 不要凭空添加该列。
--   * model_version.map50_95 由实体上的 @TableField("map50_95") 指定，列名不可改写。
--   * 训练产物中的 results.csv / args.yaml 存放在 yolo-models 桶的 task-{任务id}/ 前缀下
--     （由 Java 下发的 storagePrefix 决定，旧数据仍是 {版本号}/，读取不受影响）；
--     手动上传的权重放在 uploads/{uuid}/ 下。版本号已改为「系列内唯一、跨系列可重复」，
--     不能再拿版本号当存储目录，否则不同系列会同名互相覆盖。
--     回调字段 resultsCsvKey、argsYamlKey 当前不落库，故表中没有对应列。
--   * base_model 无需手工插入数据：在前端「基础模型」页点「同步官方清单」即可生成。
--   * sys_user 目前没有任何接口使用它（平台无登录鉴权），保留表仅为兼容既有库。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `yolo_manager`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `yolo_manager`;

-- -----------------------------------------------------------------------------
-- 数据集
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `dataset` (
    `id`          bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        varchar(100)  NOT NULL COMMENT '数据集名称（最长 100）',
    `description` varchar(500)  DEFAULT NULL COMMENT '描述（最长 500）',
    `train_ratio` decimal(3, 2) NOT NULL DEFAULT '0.80' COMMENT '训练集划分比例，其余为验证集',
    `status`      varchar(20)   NOT NULL DEFAULT 'BUILDING' COMMENT 'READY / BUILDING',
    `created_at`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '数据集';

-- -----------------------------------------------------------------------------
-- 数据集图片（原图尺寸与所属划分）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `dataset_image` (
    `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dataset_id` bigint       NOT NULL COMMENT '所属数据集',
    `file_name`  varchar(200) NOT NULL COMMENT '原始文件名',
    `rustfs_key` varchar(500) NOT NULL COMMENT '对象存储 key（{datasetId}/images/...）',
    `width`      int          DEFAULT '0' COMMENT '原图像素宽',
    `height`     int          DEFAULT '0' COMMENT '原图像素高',
    `split`      varchar(10)  DEFAULT 'train' COMMENT 'train / val / test',
    PRIMARY KEY (`id`),
    KEY `idx_dataset` (`dataset_id`),
    CONSTRAINT `fk_image_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '数据集图片';

-- -----------------------------------------------------------------------------
-- 标签（类别），同一数据集内 code 唯一
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `label` (
    `id`         bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`       varchar(50) NOT NULL COMMENT '标签名称',
    `code`       int         NOT NULL COMMENT 'YOLO 类别编号（导出 txt 的首列）',
    `color`      varchar(20) DEFAULT NULL COMMENT '标注框显示颜色',
    `dataset_id` bigint      NOT NULL COMMENT '所属数据集',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dataset_code` (`dataset_id`, `code`),
    KEY `idx_dataset` (`dataset_id`),
    CONSTRAINT `fk_label_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '数据集标签';

-- -----------------------------------------------------------------------------
-- 在线标注框（归一化坐标，与 YOLO txt 语义一致）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `annotation` (
    `id`       bigint         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `image_id` bigint         NOT NULL COMMENT '所属图片',
    `label_id` bigint         NOT NULL COMMENT '所属标签',
    `x_center` decimal(10, 6) NOT NULL COMMENT '归一化中心 X（0~1）',
    `y_center` decimal(10, 6) NOT NULL COMMENT '归一化中心 Y（0~1）',
    `width`    decimal(10, 6) NOT NULL COMMENT '归一化宽度（0~1）',
    `height`   decimal(10, 6) NOT NULL COMMENT '归一化高度（0~1）',
    PRIMARY KEY (`id`),
    KEY `idx_image` (`image_id`),
    KEY `idx_label` (`label_id`),
    CONSTRAINT `fk_ann_image` FOREIGN KEY (`image_id`) REFERENCES `dataset_image` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ann_label` FOREIGN KEY (`label_id`) REFERENCES `label` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '标注框';

-- -----------------------------------------------------------------------------
-- 训练任务（逐 epoch 指标只存 Redis，本表仅保留状态与进度）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `train_task` (
    `id`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dataset_id`       bigint      NOT NULL COMMENT '训练使用的数据集',
    `model_base`       varchar(50) NOT NULL DEFAULT 'yolov8n' COMMENT '基底模型名（yolov8n / yolov11m …）',
    `base_model_type`  varchar(20) NOT NULL DEFAULT 'BASE' COMMENT 'BASE=用基础权重新训，TRAINED=在已训练模型上继续训练',
    `base_version_id`  bigint       DEFAULT NULL COMMENT 'base_model_type=TRAINED 时的源 model_version.id',
    `version_bump`     varchar(10)  DEFAULT 'MINOR' COMMENT '版本步长：MINOR=+0.1，MAJOR=+1.0',
    `expected_version` varchar(50)  DEFAULT NULL COMMENT '本次训练成功后预期注册的版本号',
    `epochs`           int         NOT NULL DEFAULT '100' COMMENT '训练轮数（1~1000）',
    `batch_size`       int         NOT NULL DEFAULT '16' COMMENT '批次大小（1~256）',
    `imgsz`            int         NOT NULL DEFAULT '640' COMMENT '输入尺寸（32~4096）',
    `status`           varchar(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / QUEUED / RUNNING / SUCCESS / FAILED / CANCELLED',
    `progress`         int         NOT NULL DEFAULT '0' COMMENT '当前完成的 epoch 数',
    `error_msg`        text COMMENT '失败原因',
    `started_at`       datetime     DEFAULT NULL COMMENT '开始时间',
    `finished_at`      datetime     DEFAULT NULL COMMENT '结束时间',
    `created_at`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset` (`dataset_id`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_task_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '训练任务';

-- -----------------------------------------------------------------------------
-- 模型版本（uuid 供对外识别接口使用，is_active 标记当前上线版本）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `model_version` (
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `uuid`            varchar(32)  DEFAULT NULL COMMENT '对外唯一标识（32 位 UUID）',
    `version`         varchar(50)  NOT NULL COMMENT '版本号，如 v1.0.0（系列内递增，跨系列可重复）',
    `name`            varchar(100) DEFAULT NULL COMMENT '模型名称（可编辑，注册时生成默认名）',
    `series_id`       bigint       DEFAULT NULL COMMENT '系列根模型 id（初次训练/首次上传的模型；续训与同名上传归属同系列）',
    `task_id`         bigint       DEFAULT NULL COMMENT '产出该版本的训练任务',
    `best_pt_key`     varchar(500) DEFAULT NULL COMMENT 'best.pt 在 yolo-models 桶内的 key',
    `last_pt_key`     varchar(500) DEFAULT NULL COMMENT 'last.pt 在 yolo-models 桶内的 key',
    `map50`           decimal(5, 4) DEFAULT NULL COMMENT 'mAP@0.50',
    `map50_95`        decimal(5, 4) DEFAULT NULL COMMENT 'mAP@0.50:0.95',
    `is_active`       tinyint      NOT NULL DEFAULT '0' COMMENT '是否为当前上线版本（0/1）',
    `last_trained_at` datetime     DEFAULT NULL COMMENT '最后训练完成时间',
    `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uuid` (`uuid`),
    KEY `idx_task` (`task_id`),
    KEY `idx_series` (`series_id`),
    CONSTRAINT `fk_model_task` FOREIGN KEY (`task_id`) REFERENCES `train_task` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '模型版本';

-- -----------------------------------------------------------------------------
-- 推理测试记录（平台内「调用测试」历史）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inference_test` (
    `id`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_version_id`   bigint       NOT NULL COMMENT '被测试的模型版本',
    `image_key`          varchar(500) NOT NULL COMMENT '测试图片在存储中的 key',
    `result_json`        json         DEFAULT NULL COMMENT '检测结果 JSON',
    `inference_time_ms`  int          DEFAULT '0' COMMENT '推理耗时（毫秒）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_model` (`model_version_id`),
    CONSTRAINT `fk_test_model` FOREIGN KEY (`model_version_id`) REFERENCES `model_version` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '推理测试记录';

-- -----------------------------------------------------------------------------
-- 基础预训练权重目录（由「基础模型」页同步官方清单后写入）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `base_model` (
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_name`    varchar(64)  NOT NULL COMMENT '平台模型名（如 yolov8n / yolov11m / yolov26x）',
    `family`        varchar(16)  NOT NULL COMMENT '模型家族（v8 / v11 / v26 …）',
    `scale`         varchar(4)   NOT NULL COMMENT '尺度 n/s/m/l/x',
    `official_file` varchar(128) NOT NULL COMMENT 'Ultralytics 官方 .pt 文件名（如 yolov8n.pt / yolo26n.pt）',
    `download_url`  varchar(512) DEFAULT NULL COMMENT '官方下载直链（以 GitHub Release 为源）',
    `size_bytes`    bigint       DEFAULT '0' COMMENT '本地文件字节大小（同步后回填）',
    `status`        varchar(16)  NOT NULL DEFAULT 'MISSING' COMMENT 'MISSING / DOWNLOADING / DOWNLOADED / FAILED',
    `error_msg`     text COMMENT '上次下载失败原因',
    `enabled`       tinyint      NOT NULL DEFAULT '1' COMMENT '是否在训练表单中可见（0=下架）',
    `local_path`    varchar(512) DEFAULT NULL COMMENT '训练机上对应的绝对路径',
    `last_sync_at`  datetime     DEFAULT NULL COMMENT '最后一次状态同步时间',
    `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_name` (`model_name`),
    KEY `idx_family` (`family`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '基础预训练权重目录';

-- -----------------------------------------------------------------------------
-- 系统用户（预留表：当前无登录与鉴权逻辑，无任何接口读写）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`         bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   varchar(50) NOT NULL COMMENT '登录名',
    `password`   varchar(100) NOT NULL COMMENT '口令（库内存 BCrypt 摘要）',
    `role`       varchar(20) NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN / USER',
    `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '系统用户（预留）';

-- -----------------------------------------------------------------------------
-- 存量库原地升级：2026-11-26 模型系列化
-- 上面的 CREATE TABLE IF NOT EXISTS 对已存在的表不生效，所以已有库靠这一段补齐。
-- 语义变化：版本号由「全局唯一」改为「系列内唯一、跨系列可重复」——
--   1) model_version 新增 series_id 与 idx_series；
--   2) 删除全局唯一键 uk_version（否则同名上传与跨系列同版本会被拒）；
--   3) 回填：历史记录先各自成系列，再沿续训链把 TRAINED 产物并入源模型系列。
-- 全部先查 information_schema 再执行，不使用 DELIMITER，重复跑不会报错。
-- -----------------------------------------------------------------------------
SET @sch = DATABASE();

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND COLUMN_NAME = 'series_id');
SET @ddl = IF(@has_col = 0,
    'ALTER TABLE `model_version` ADD COLUMN `series_id` bigint DEFAULT NULL COMMENT ''系列根模型 id（初次训练/首次上传的模型；续训与同名上传归属同系列）'' AFTER `name`',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_uk = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'uk_version');
SET @ddl = IF(@has_uk > 0, 'ALTER TABLE `model_version` DROP INDEX `uk_version`', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'idx_series');
SET @ddl = IF(@has_idx = 0, 'ALTER TABLE `model_version` ADD KEY `idx_series` (`series_id`)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量数据先各自成系列（已回填过的行不会被再次改动）
UPDATE `model_version` SET `series_id` = `id` WHERE `series_id` IS NULL;

-- TRAINED 任务产物并入源模型系列；执行两遍以覆盖两级续训链，更深层级可重复执行本段
UPDATE `model_version` mv JOIN `train_task` t ON mv.`task_id` = t.`id`
    JOIN `model_version` src ON t.`base_version_id` = src.`id`
    SET mv.`series_id` = src.`series_id`
    WHERE t.`base_model_type` = 'TRAINED' AND mv.`series_id` <> src.`series_id`;
UPDATE `model_version` mv JOIN `train_task` t ON mv.`task_id` = t.`id`
    JOIN `model_version` src ON t.`base_version_id` = src.`id`
    SET mv.`series_id` = src.`series_id`
    WHERE t.`base_model_type` = 'TRAINED' AND mv.`series_id` <> src.`series_id`;
