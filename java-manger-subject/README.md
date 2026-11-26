# java-manger-subject

YOLO 视觉识别模型管理平台 · **管理后端**（Spring Boot 3.3.5 / Java 17）

平台内部文档：[项目总体说明](../README.md) · [训练与推理服务](../python-yolo-subject/README.md) · [前端](../vue-subject/README.md)

[English](README_EN.md)

## 1. 职责

- 数据集、图片、标签、在线标注的管理与持久化
- 训练任务编排：校验数据集 → 落库 → 投递训练服务（HTTP 或 Redis 队列）
- 接收训练服务回调，注册模型版本（同系列递增、UUID 自动生成）
- 模型版本管理：上传注册、改名、激活上线、下载、删除、推理测试与测试记录
- 基础预训练权重目录管理（官方清单同步、状态回写、启停、下载触发）
- 对外推理识别接口（`/api/inference/*`，第三方系统直接调用）
- 在线标注 → YOLO 格式 `.txt` 的异步导出；COCO/YOLO/图片压缩包的异步导入
- 对象存储读写（RustFS/MinIO，S3 协议；`local` 模式可退化为本地磁盘）

> 当前**没有登录与鉴权**：`entity/SysUser.java` 与 `mapper/SysUserMapper.java` 已存在但无任何 Controller/拦截器使用，所有接口匿名可访问。

## 2. 技术栈

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.3.5 |
| JDK | 17（`maven.compiler.source/target`） |
| MyBatis-Plus | 3.5.9（`mybatis-plus-spring-boot3-starter`） |
| AWS SDK v2 S3 | 2.29.24 |
| Hutool | 5.8.32 |
| SpringDoc OpenAPI | 2.6.0（Swagger UI） |
| MySQL Connector/J / Redis / Lombok | 由 parent 管理 |

## 3. 目录结构

```
src/main/java/com/zyl/yolo/
├── YoloManagerApplication.java   # 启动类（mainClass 与 pom 中 exec/spring-boot 插件一致）
├── common/                       # Result、ResultCode、BusinessException、GlobalExceptionHandler、RedisUtils
├── config/                       # OpenApiConfig、RedisConfig、RestTemplateConfig、S3Config、WebMvcConfig
├── controller/                   # 9 个 REST 控制器（见第 4 节）
├── service/                      # 11 个服务：Dataset/DatasetImage/DatasetImport/Export/Annotation/
│                                 #   Label/Train/ModelVersion/BaseModel/Inference/DatasetJobRegistry
├── mapper/                       # MyBatis-Plus BaseMapper（9 个）
├── entity/                       # 数据库实体（9 个，均带 @TableName）
├── dto/                          # 请求/响应对象（23 个）
└── storage/                      # StorageService 抽象 + LocalStorageService / S3StorageService / BucketUtils

src/main/resources/
├── application.yml               # 主配置
├── application-prod.yml          # 生产 profile（仅列差异项）
└── db/
    ├── schema.sql                          # 建库建表 + 存量库迁移段（幂等，由部署脚本的 db-init 容器自动执行）
    └── patch-2026-11-26-model-series.sql   # 2026-11-26 模型系列化补丁（供已有库手工升级，与 schema.sql 内迁移段等价）
```

> 建库建表以 `src/main/resources/db/schema.sql` 为准（详见第 6 节），未引入 Flyway/Liquibase，也不做启动自动建表；
> 手工部署时需自行导入，Docker 部署则由 `db-init` 容器代劳（它按文件名字母顺序执行 `db/*.sql`，两个脚本都做了存在性判断，重复跑不报错）。字段含义以 `entity/*.java` 的 `@TableName` 与属性为准。

## 4. 接口一览

在线文档：启动后访问 `http://localhost:8080/swagger-ui.html`（OpenAPI 标题「YOLO 视觉识别模型管理平台 API」，version 1.0.0）。

| 分组 | 方法 + 路径 | 说明 |
|---|---|---|
| 数据集 | `POST /api/dataset` | 创建数据集 |
| | `GET /api/dataset` · `GET /api/dataset/{id}` | 列表 / 详情 |
| | `DELETE /api/dataset/{id}` | 删除数据集 |
| | `POST /api/dataset/{id}/import` | 压缩包异步导入；`format` = AUTO（默认自动探测）/ COCO / YOLO / IMAGES，`jsonPath` 指定包内 COCO JSON 位置 |
| | `GET /api/dataset/{id}/import-status` | 导入进度（NONE/PENDING/PROCESSING/DONE/FAILED） |
| 图片 | `POST /api/dataset/{datasetId}/upload` | 批量上传图片 |
| | `GET /api/dataset/{datasetId}/images` | 图片列表 |
| | `DELETE /api/dataset/{datasetId}/images/{imageId}` | 删除图片 |
| | `POST /api/dataset/{datasetId}/export` | 启动 YOLO 标注异步导出 |
| | `GET /api/dataset/{datasetId}/export-status` | 导出进度轮询 |
| 标签 | `GET/POST /api/dataset/{datasetId}/label` | 标签列表 / 创建 |
| | `PUT /api/dataset/{datasetId}/label/{labelId}` | 重命名（已有标注与导出同步生效） |
| | `DELETE /api/dataset/{datasetId}/label/{labelId}` | 删除标签 |
| 标注 | `POST /api/annotation` | 保存/更新单条标注 |
| | `POST /api/annotation/sync/{imageId}` | 整张图片标注批量同步 |
| | `GET /api/annotation/{imageId}` | 读取图片标注 |
| | `DELETE /api/annotation/{annotationId}` | 删除标注 |
| 训练 | `POST /api/train` | 创建并投递训练任务 |
| | `GET /api/train` · `GET /api/train/{id}` | 任务列表 / 详情 |
| | `GET /api/train/base-models` | 训练表单可选基底模型（BASE + TRAINED 两类） |
| | `GET /api/train/{id}/metrics` | epoch 指标（优先读 Redis 实时缓存） |
| | `POST /api/train/{id}/cancel` · `DELETE /api/train/{id}` | 取消 / 删除（运行中任务须先取消） |
| | `POST /api/train/callback` | 供训练服务回调，注册模型版本 |
| 模型 | `GET /api/model` | 版本列表（uuid、版本号、模型名、系列根 ID、基底模型、训练集、上线状态） |
| | `POST /api/model/upload` | 直接上传 `.pt` 注册新版本（文件名与库内模型同名则并入该系列） |
| | `PUT /api/model/{id}/name` | 编辑模型名称（≤100 字符） |
| | `POST /api/model/{id}/activate` | 激活上线（其余版本自动下线） |
| | `POST /api/model/{id}/test` | 上传测试图执行推理，返回结果与服务端标注图 |
| | `GET /api/model/{id}/tests` | 历史测试记录 |
| | `GET /api/model/{id}/download` | 附件下载 `best.pt`（文件名 `{版本号}.pt`） |
| | `DELETE /api/model/{id}` | 删除版本（无人共享的 S3 权重一并清理） |
| 基础模型 | `GET /api/base-model/list` | 平台基底模型列表 |
| | `POST /api/base-model/refresh-status` · `/sync-official` | 刷新本地状态 / 同步官方权重清单 |
| | `POST /api/base-model/download` · `POST /api/base-model/{id}/enabled` · `DELETE /api/base-model/{id}` | 触发下载 / 启停 / 删除 |
| 对外识别 | `POST /api/inference/detect` | 上传图片做目标检测；`modelUuid` 可省略，默认取当前上线版本 |
| | `GET /api/inference/models` | 可用模型（uuid、version、isActive、mAP） |
| 存储 | `GET /api/storage/{bucket}/**` | S3 模式下代理对象存储内容供前端取图 |
| 静态 | `GET /storage/**` | 仅 `storage.type=local` 时由 `WebMvcConfig` 映射本地磁盘 |

创建数据集请求体（`DatasetCreateRequest`）：`name`（必填、≤100）、`description`（≤500）、`trainRatio`（训练集划分比例，默认 0.8）。

创建训练任务请求体（`TrainTaskCreateRequest`）：

| 字段 | 约束 | 说明 |
|---|---|---|
| `datasetId` | 必填 | 数据集 ID |
| `modelBase` | `baseModelType=BASE` 时必填 | 基底模型名，如 `yolov8n` |
| `baseModelType` | `BASE` / `TRAINED`，默认 `BASE` | 新训 / 继续训练 |
| `baseVersionId` | `TRAINED` 时必填 | 源 `model_version.id` |
| `versionBump` | `MINOR`(+0.1，默认) / `MAJOR`(+1.0) | 继续训练时的版本步长（系列内递增） |
| `epochs` | 1 ~ 1000 | 训练轮数 |
| `batchSize` | 1 ~ 256 | 批次大小 |
| `imgsz` | 32 ~ 4096 | 输入尺寸 |

## 5. 统一返回结构

除下载、Swagger 与存储代理外，所有接口返回 `Result<T>`：

```json
{ "code": 200, "message": "success", "data": {} }
```

`code` 非 200 时由 `GlobalExceptionHandler` 将 `BusinessException(ResultCode)` 转换为该结构；前端 `request.js` 拦截器据此弹错并解包 `data`。

## 6. 数据模型

建库建表脚本：[`src/main/resources/db/schema.sql`](src/main/resources/db/schema.sql)（MySQL 8.0+，`utf8mb4_0900_ai_ci`，建表语句全量 `CREATE TABLE IF NOT EXISTS`，可重复执行）。
脚本末尾还带一段「存量库原地升级」迁移（先查 `information_schema` 再 `PREPARE/EXECUTE`，不用 `DELIMITER`），用于补齐 2026-11-26 的模型系列化变更；
已有库不想跑全量脚本时，可单独执行等价的 [`patch-2026-11-26-model-series.sql`](src/main/resources/db/patch-2026-11-26-model-series.sql)（同样幂等）。
脚本从现网库导出后与 `entity/*.java` 逐一核对，并重写了早期存成 `?` 的中文注释；根目录的
`docker-compose.yml` 会由 `db-init` 容器自动执行 `db/*.sql`（它同时适用于内置 MySQL 与外部 MySQL，后者需打开 `auto-ddl` profile，详见 [根 README](../README.md#一键部署docker推荐)）。

| 表名 | 实体 | 用途 |
|---|---|---|
| `dataset` | Dataset | 数据集 |
| `dataset_image` | DatasetImage | 图片（含 `split` 划分信息） |
| `label` | Label | 数据集标签 |
| `annotation` | Annotation | 标注框（归一化中心点与宽高） |
| `train_task` | TrainTask | 训练任务与状态 |
| `model_version` | ModelVersion | 模型版本、系列根 id、权重 key、上线标记 |
| `base_model` | BaseModel | 基底/预训练权重目录 |
| `inference_test` | InferenceTest | 推理测试记录 |
| `sys_user` | SysUser | 预留，未接入鉴权 |

### 6.1 模型版本系列化（`model_version.series_id`）

版本号语义为「**系列内唯一、跨系列可重复**」，`series_id` 存系列根模型的 `id`：

| 来源 | 系列归属 | 版本规则 |
|---|---|---|
| 基础模型初训（`BASE`） | 新系列（`series_id` = 自身 id） | 固定 `v1.0.0` |
| 续训（`TRAINED`） | 并入基底模型所在系列 | 基底版本按 MINOR/MAJOR 升级，系列内去重递增 |
| 上传·文件名与库内模型同名 | 并入同名模型系列 | 系列内最大版本 + PATCH，冲突自动递增 |
| 上传·新名称 | 新系列 | `v1.0.0` |

配套约束：活跃任务（CREATED/QUEUED/RUNNING）的 `expected_version` 计入系列占用，避免并发训练/回调撞号；
版本比较走数值化逐段比较（修复字符串比较导致 `v1.9.0 > v1.10.0` 的错误）；`series_id` 为空的历史数据以自身 id 视为独立系列。
前端「模型版本」页按 `seriesId` 折叠为树表（系列根为父行）；「基底模型」列显示续训链根模型的**架构名**（如 `yolo11n`），根为手动上传时显示「手动上传」。
权重存储 key 随之改为唯一目录，避免跨系列同版本号互相覆盖：训练产物 `task-{taskId}/`（Java 下发 `storagePrefix`，旧 Python 缺省回退版本号），
手动上传 `uploads/{uuid}/`，历史数据仍为 `{version}/` 并原样可读。

MyBatis-Plus 全局配置：下划线转驼峰、`id-type=auto`、逻辑删除字段 `deleted`（1 删除 / 0 正常）。

## 7. 配置说明

配置文件分两层，`application-prod.yml` 只写差异项，其余继承 `application.yml`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 8080（prod：12391） | 管理后端端口 |
| `spring.datasource.url` | 直接写在 yml 中 | MySQL 8，库名 `yolo_manager` |
| `spring.data.redis.database` | 5 | **必须与训练服务 `REDIS_METRICS_DB` 一致**，否则前端看不到实时指标 |
| `spring.servlet.multipart.max-file-size/max-request-size` | 5120MB | 支撑数千张图 / 数 GB 压缩包导入 |
| `yolo.storage.type` | s3 | `s3`（RustFS/MinIO）或 `local`（本地磁盘，开发用） |
| `yolo.storage.s3.bucket-datasets/-models/-tests` | yolo-datasets / yolo-models / yolo-tests | 三类对象分桶 |
| `yolo.train-service.base-url` | http://localhost:8000 | FastAPI 地址（prod 指向训练机） |
| `yolo.train-service.mode` | http | `http` 调 `/api/train/start`；`redis` 写入 `train:queue` |
| `yolo.callback.token` | 空 | 回调鉴权 token，留空则不校验 |
| `yolo.callback.base-url` | `${YOLO_CALLBACK_BASE_URL:http://localhost:8080}` | 提供给训练服务回调的对外地址，跨机部署必须覆盖 |

**只有 `STORAGE_LOCAL_ROOT`、`STORAGE_PUBLIC_BASE_URL`、`YOLO_CALLBACK_BASE_URL` 三个值写成了 `${VAR:default}` 占位符**。数据库、Redis、S3 端点与账号目前是 yml 明文硬编码；`application.yml` 里的内网地址与口令不适用于其他环境，有两种覆盖方式：

- **Docker 一键部署（推荐）**：不需要改 yml。Spring Boot relaxed binding 会用同名环境变量覆盖任意属性，`docker-compose.yml` 里的 `SPRING_DATASOURCE_URL` / `SPRING_DATA_REDIS_HOST` / `YOLO_STORAGE_S3_ENDPOINT` / `YOLO_TRAIN_SERVICE_BASE_URL` 等就是靠这个生效的，具体值由脚本生成的 `.env` 控制：在 `deploy.sh` / `deploy.ps1` 的【配置区】改（不提问部署）或走交互式引导逐项确认。引导里选「用已有实例」后，对应容器不再启用，这些地址就指向你已有的服务。
- **手工部署**：按需把 yml 改成 `${MYSQL_HOST:localhost}` 形式，或用启动参数 `--spring.datasource.url=` / `--yolo.storage.s3.endpoint=` 覆盖。

> 本目录下的 `.env.example` 只是变量清单，Spring Boot **不会**自动读取 `.env` 文件，请勿指望它直接生效。

## 8. 与训练服务的交互

| 方向 | 目标接口 | 说明 |
|---|---|---|
| Java → Python | `POST /api/train/start` | 投递训练任务（`mode=http`，payload 含 `callbackUrl` / `callbackToken` / `expectedVersion`） |
| Java → Python | `POST /api/train/{taskId}/cancel` | 取消：FastAPI 写 `train:cancel:{taskId}` 并 revoke Celery 任务（失败仅记日志，不阻断本地置 CANCELLED） |
| Java → Python | `POST /api/inference/predict` | 模型测试与对外识别 |
| Java → Python | `GET /api/base-models/local` / `/official`、`POST /api/base-models/download` | 权重清单、状态与下载 |
| Python → Java | `POST /api/train/callback` | 训练终态回调：回写 status/progress/map50/map5095 与权重 key，注册模型版本 |

训练状态与日志由前端直接轮询 Java（`GET /api/train/{id}`、`/metrics`），**Java 不会反向去拉 Python 的 status/logs**；Python 侧的 `/api/train/{task_id}/status`、`/logs` 主要供运维与联调使用。

Redis 键约定（跨语言，务必保持一致）：

| Key | 所在库 | 写入方 | 读取方 |
|---|---|---|---|
| `train:metrics:{taskId}` | `database 5`（TTL 7 天） | Python | Java `GET /api/train/{id}/metrics` |
| `train:cancel:{taskId}` | 指标库（TTL 1 天） | Python（收到 Java 取消请求时写入） | Python worker 每 epoch 轮询 |
| `train:running:{taskId}` | broker 库 | Python 训练执行锁（SET NX） | Python |
| `train:queue` | broker 库 | Java（仅 `mode=redis`） | Celery |

导入/导出为后台线程池任务：`DatasetJobRegistry` 对同一数据集加互斥锁，锁定期内禁止删除数据集、增删图片/标签/标注以及重复触发导入导出。

## 9. 本地运行

```bash
# 1. 准备 MySQL 库表：导入 src/main/resources/db/schema.sql（幂等，含建库语句）
#    mysql -h <host> -P <port> -u <user> -p < src/main/resources/db/schema.sql
# 2. 按环境覆盖配置：改 application.yml，或用 --spring.datasource.url= 等启动参数

mvn clean package -DskipTests
java -jar target/yolo-manager.jar            # 端口 8080

# 跨机部署时让训练服务能回调回来
java -jar target/yolo-manager.jar --YOLO_CALLBACK_BASE_URL=http://<本机IP>:8080
# 使用生产 profile
java -jar target/yolo-manager.jar --spring.profiles.active=prod
```

仓库根目录 `target/` 下产物名为 `yolo-manager.jar`（`finalName` 指定）。模块内没有 `mvnw`，需要本机 Maven ≥ 3.8。

不想装 JDK / Maven 时，直接用根目录的 `docker-compose.yml`（服务名 `manager`）：[Dockerfile](Dockerfile) 为多阶段构建，容器内完成 `mvn package`，配置全部由 compose 通过环境变量注入。

## 10. 注意事项与现状

1. **无鉴权 + CORS 全开**：`WebMvcConfig` 对 `/**` 放行所有来源与方法，接口可被任意调用，只适合内网或前置网关保护。
2. **配置明文**：`application.yml` 与 `.env.example` 含真实内网地址与账号口令，对外开源前需替换为占位值。
3. `GET /api/train/{id}/metrics` 只读 Redis 缓存（key `train:metrics:{taskId}`，TTL 7 天），缓存过期后返回空数组；`train_task` 表**不存**逐 epoch 指标（只有 status/progress），要长期保留曲线得用归档到存储的 `results.csv`（回调字段 `resultsCsvKey`）重建；最终 mAP@50 / mAP@50-95 存在 `model_version`。
4. 训练读的是**已导出到存储**的标注文件，未执行 `POST /api/dataset/{id}/export` 就直接训练会失败。
5. 容器化部署已就绪：本模块有 [Dockerfile](Dockerfile)（maven:3.9-eclipse-temurin-17 构建 + eclipse-temurin:17-jre 运行），编排文件在仓库根目录 `docker-compose.yml`，一键脚本为 `deploy.sh` / `deploy.ps1`（英文提示版 `deploy_en.sh` / `deploy_en.ps1`），详见 [根 README 的“一键部署（Docker）”](../README.md#一键部署docker推荐)。
6. 镜像内不含任何环境相关口令；`application.yml` 里的内网地址仅作为本地开发默认值，Docker 部署全部被环境变量覆盖。
