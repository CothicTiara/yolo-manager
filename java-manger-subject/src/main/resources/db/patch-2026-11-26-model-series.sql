-- =============================================================================
-- 补丁 2026-11-26：模型版本系列化（与 schema.sql 内的同名迁移段等价，二选一执行）
--
-- 适用场景：
--   1) 已有库原地升级：按上面的执行方式带上库名跑本文件即可；
--   2) 全新库（尚无 model_version 表）：下面每一段都带存在性判断，整段自动跳过，不报错；
--   3) Docker 部署：db-init 容器按文件名字母顺序执行 db/*.sql（本文件在 schema.sql 之前），
--      且不预选库，因此 DATABASE() 为 NULL → 本文件自然跳过，系列化变更由 schema.sql
--      末尾的同名迁移段完成（两段语义完全一致）。
--
-- 语义变化：版本号由「全局唯一」改为「系列内唯一、跨系列可重复」
--   1) 删除全局唯一键 uk_version（否则同名上传与跨系列同版本会被拒）；
--   2) 新增 series_id（系列根模型 id）与 idx_series；
--   3) 回填：历史记录先各自成系列，再沿续训链把 TRAINED 产物并入源模型系列。
--
-- 说明：权重存储 key 已由「{版本号}/」改为「task-{taskId}/」（训练产物）与
--       「uploads/{uuid}/」（手动上传），去掉版本唯一键不会引起权重互相覆盖。
--
-- 执行方式（外部库示例，口令请用实际值或环境变量注入，勿写入仓库）：
--   mysql -h <host> -P 3306 -u root -p --default-character-set=utf8mb4 yolo_manager < patch-2026-11-26-model-series.sql
-- 建议先备份：mysqldump -u root -p yolo_manager model_version > model_version.bak.sql
--
-- 全部语句先查 information_schema 再经 PREPARE 执行，不使用 DELIMITER，
-- 因此 JDBC / mysql CLI / 管道执行都可用。
-- =============================================================================

SET @sch = DATABASE();

-- ---------------------------------------------------------------- 0. 前置判断
SET @has_tbl = (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version');
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND COLUMN_NAME = 'series_id');
SET @has_uk = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'uk_version');
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'idx_series');

-- ------------------------------------------------- 1. 删除版本号全局唯一约束
SET @ddl = IF(@has_tbl = 1 AND @has_uk > 0,
    'ALTER TABLE `model_version` DROP INDEX `uk_version`', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------- 2. 新增 series_id
SET @ddl = IF(@has_tbl = 1 AND @has_col = 0,
    'ALTER TABLE `model_version` ADD COLUMN `series_id` bigint DEFAULT NULL COMMENT ''系列根模型 id（初次训练/首次上传的模型；续训与同名上传归属同系列）'' AFTER `name`',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------ 3. 新增系列索引
SET @ddl = IF(@has_tbl = 1 AND @has_idx = 0,
    'ALTER TABLE `model_version` ADD KEY `idx_series` (`series_id`)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -------------------------------------------- 4. 存量回填：先各自成独立系列
SET @ddl = IF(@has_tbl = 1,
    'UPDATE `model_version` SET `series_id` = `id` WHERE `series_id` IS NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------ 5. 续训产物并入源模型系列（两遍覆盖两级续训链）
-- 更深层的续训链可重复执行本段两条 UPDATE，直到受影响行数为 0。
SET @ddl = IF(@has_tbl = 1,
    'UPDATE `model_version` mv JOIN `train_task` t ON mv.`task_id` = t.`id` JOIN `model_version` src ON t.`base_version_id` = src.`id` SET mv.`series_id` = src.`series_id` WHERE t.`base_model_type` = ''TRAINED'' AND mv.`series_id` <> src.`series_id`',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 6. 结果自检
-- 前三列走 information_schema，表不存在时计数为 0，不会报错。
SELECT IF(@has_tbl = 0, '全新库（尚无 model_version 表），本补丁已跳过', '补丁执行完成') AS patch_result,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND COLUMN_NAME = 'series_id') AS has_series_id,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'uk_version') AS still_uk_version,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = @sch AND TABLE_NAME = 'model_version' AND INDEX_NAME = 'idx_series') AS has_idx_series;

-- 表存在时再统计仍未归属系列的行数（应为 0）
SET @ddl = IF(@has_tbl = 1,
    'SELECT COUNT(*) AS unassigned_rows FROM `model_version` WHERE `series_id` IS NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
