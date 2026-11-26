# python-yolo-subject

YOLO 视觉识别模型管理平台 · **训练与推理服务**（FastAPI + Celery + Ultralytics YOLO）

平台内部文档：[项目总体说明](../README.md) · [管理后端](../java-manger-subject/README.md) · [前端](../vue-subject/README.md)

[English](README_EN.md)

## 1. 职责

- 接收管理后端投递的训练任务，交给 Celery 异步执行 Ultralytics YOLO 训练
- 训练前从对象存储并发拉取图片与标注、生成 `data.yaml`；训练后归档 `best.pt` / `last.pt` / `results.csv` / `args.yaml`
- 逐 epoch 回传指标：写 Redis 实时缓存（供前端曲线，TTL 7 天）；MySQL 只更新任务状态与进度，逐 epoch 指标**不落库**，历史重建靠归档到 S3 的 `results.csv`
- 训练完成回调管理后端 `/api/train/callback` 注册模型版本
- 提供推理接口：平台内「调用测试」与「对外识别」都走这里
- 维护基础预训练权重：解析官方清单、后台下载、上报本地状态

## 2. 模块结构

```
app/
├── main.py                  # FastAPI 入口（12 个路由）+ lifespan 初始化存储与推理服务 + CORS
├── worker.py                # Celery 应用与 yolo.train_task 定义（去重、执行锁、取消检测）
├── config.py                # Pydantic Settings，读 .env，派生 broker/result/mysql URL
├── models/schemas.py        # 请求/响应模型（TrainStartRequest、TrainMetric、BaseWeightDownloadRequest…）
├── services/
│   ├── training.py          # 训练编排：数据准备 → YOLO 训练 → 指标解析 → 产物归档 → 回调
│   ├── inference.py         # 推理：下载权重与图片、执行检测、生成服务端标注图
│   ├── base_models.py       # 官方权重清单（ultralytics/assets）与本地目录扫描/后台下载
│   └── storage.py           # build_storage()：S3(RustFS/MinIO) 与本地磁盘两种实现
└── utils/
    ├── db.py                # MySQL 任务状态/进度与模型版本回写 + Redis 指标缓存（含 CREATED→QUEUED 原子条件更新）
    ├── yolo_utils.py        # data.yaml 生成、results.csv 解析
    ├── yolov13_compat.py    # YOLOv13 自定义模块兼容层（注入 12 个类 + AAttn 自适应替换，加载外部权重前调用）
    └── logging_utils.py     # 日志配置（logs/ 目录）
```

## 3. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，返回 `status` / `yolo_installed` / `time` |
| POST | `/api/train/start` | 接收训练任务并投递 Celery（同步 `def`，避免 broker 阻塞事件循环） |
| POST | `/api/train/{task_id}/cancel` | 写取消标记 `train:cancel:{task_id}`（TTL 1 天），worker 轮询后中断 |
| GET | `/api/train/{task_id}/status` | 训练状态 |
| GET | `/api/train/{task_id}/logs` | 训练日志行 |
| GET | `/api/train/{task_id}/metrics` | epoch 指标，优先读 Redis 实时缓存 |
| POST | `/api/model/register` | 注册模型版本（管理后端回调链路的等价入口） |
| POST | `/api/inference/predict` | 执行推理，返回检测框、耗时、服务端标注图（base64 + 存储 key）、原图尺寸 |
| GET | `/api/inference/health` | 推理服务自检（权重加载能力等） |
| GET | `/api/base-models/local` | 扫描 `BASE_MODELS_DIR`，返回已下载 `.pt` / 在下载 `.pt.part` / 失败 `.pt.failed` |
| GET | `/api/base-models/official` | 解析 ultralytics GitHub Release 最新 tag，返回可下载的检测权重 |
| POST | `/api/base-models/download` | 为每个条目起后台下载线程，立即返回 `accepted` 列表 |

FastAPI 自带文档：`http://localhost:8000/docs`（OpenAPI JSON 在 `/openapi.json`）。

## 4. 环境要求与依赖

- Python 3.10+
- MySQL 8（库名 `yolo_manager`，与管理后端同库）
- Redis（Celery broker / result / 实时指标三用）
- 训练与推理需 GPU 环境额外安装：`pip install "ultralytics>=8.3.0" torch torchvision`

`requirements.txt` 已固定：fastapi 0.115.5、uvicorn[standard] 0.32.1、python-multipart ≥0.0.9（`Form` / `UploadFile` 必需，丢了服务启动即报错）、celery 5.4.0、kombu ≥5.3.4,<5.4、redis ≥4.5,<6、boto3 1.35.55、pydantic 2.10.2、pydantic-settings 2.6.1、PyMySQL 1.1.1、SQLAlchemy 2.0.36、opencv-python-headless 4.10.0.84、Pillow 11.0.0、requests 2.32.3、httpx 0.28.1。**ultralytics / torch / torchvision 默认注释**，因为在无 GPU 机器上体积过大且会让本地语法检查变慢 —— 需要训练/推理时手工放开。

> 未装 torch/ultralytics 时 FastAPI 依然可以启动，`/api/health` 返回 `yolo_installed: false`，训练与推理接口给出明确提示，便于先联调管理端流程。

## 5. 配置

`app/config.py` 使用 Pydantic Settings，启动时自动加载同目录 `.env`（`env_file=".env"`、`extra="ignore"`），因此**改 `.env` 后需重启进程**。变量清单与默认值见 `.env.example`，关键项：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HOST` / `PORT` | 0.0.0.0 / 8000 | 服务监听 |
| `MYSQL_HOST` … `MYSQL_DATABASE` | localhost:3306 / yolo_manager | 回写任务状态/进度与模型版本（逐 epoch 指标只进 Redis）；用户名密码在 `mysql_url()` 中做 `quote_plus` 编码，`@`、`#` 等特殊字符无需手工转义 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | Celery broker 与 result 后端；口令在 `config.py` 的 `_redis_auth()` 里做百分号编码（`quote(pw, safe='')`）后再拼进 URL，以 `YoloRedis#2026` 这类带 `#` 的默认口令可直接使用 —— 不编码会被 urlparse 当作 fragment，netloc 退化成 `:YoloRedis`，Celery 启动即报 “Port could not be cast to integer value” |
| `REDIS_DB_BROKER` / `REDIS_DB_RESULT` | 1 / 2 | Celery 队列与结果后端 |
| `REDIS_METRICS_DB` | 5 | **必须等于管理后端 `spring.data.redis.database`**，否则前端训练曲线为空 |
| `S3_ENDPOINT` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_REGION` / `S3_USE_SSL` | localhost:9000 / … / us-east-1 / false | RustFS/MinIO |
| `S3_BUCKET_DATASETS` / `_MODELS` / `_TESTS` | yolo-datasets / yolo-models / yolo-tests | 与管理后端 `yolo.storage.s3.*` 分桶名保持一致 |
| `STORAGE_TYPE` | s3 | `s3` 或 `local`（本地开发免对象存储依赖） |
| `WORK_DIR` | ./work | 训练数据临时下载目录 |
| `YOLO_PROJECT_DIR` | ./yolo_projects（生产建议 `/model/yolo_projects`） | Ultralytics 训练输出根目录 |
| `BASE_MODELS_DIR` | ./base_models（生产建议 `/model/base_models`） | 预训练权重目录，需**预先离线放置** `yolov8n/s/m/l/x.pt`、`yolov11n/s/m/l/x.pt` 等，避免运行时联网抓 GitHub |
| `DEFAULT_EPOCHS` / `DEFAULT_BATCH_SIZE` / `DEFAULT_IMGSZ` | 100 / 16 / 640 | 任务未指定时的兜底参数 |
| `DEVICE` | 空 | 留空由 ultralytics 自动选择，可强制 `cuda` / `cpu` |
| `TRAIN_WORKERS` | 8 | DataLoader 子进程数；Linux 生产建议 8，**Windows 开发机建议 0**（避免 spawn 兼容问题） |
| `CALLBACK_TOKEN` | yolo-callback-token | 回调管理后端时携带的 token；与管理后端 `yolo.callback.token` 配对（后者留空则不校验） |
| `DATASET_PREP_WORKERS` | 16 | 数据准备阶段并发下载线程数 |

## 6. 本地运行

```bash
cp .env.example .env      # 按实际环境修改数据库 / Redis / S3 / 目录

pip install -r requirements.txt
# 需要训练时再装 ultralytics / torch，轮子源决定 CPU 还是 CUDA（两者不能混用）：
#   CPU：pip install --index-url https://download.pytorch.org/whl/cpu torch torchvision
#   GPU：pip install --index-url https://download.pytorch.org/whl/cu124 torch torchvision
#   然后：pip install "ultralytics>=8.3.0"（仍走 PyPI；已装好的 torch 满足版本要求，不会被替成 CUDA 版）
# 注意不要直接 pip install --index-url https://download.pytorch.org/whl/cpu ultralytics，
# 那个索引只提供 torch/torchvision，没有 ultralytics 及其依赖。

# 终端 A：FastAPI（代码改动需自行重启，未开启 --reload）
uvicorn app.main:app --host 0.0.0.0 --port 8000

# 终端 B：Celery Worker —— 训练真正的执行者
# Linux / macOS：
celery -A app.worker:celery_app worker --loglevel=info --concurrency=1
# Windows（重要）：prefork 池会崩溃（ValueError: not enough values to unpack），必须用 --pool=solo
celery -A app.worker:celery_app worker --loglevel=info --pool=solo
```

没有 Celery worker 时，`/api/train/start` 只会把任务留在队列里，状态永远停在 queued —— 排查训练不动先确认 worker 存活。

### Docker 部署（CPU / GPU）

本模块的 [Dockerfile](Dockerfile) 由仓库根目录的 `docker-compose.yml` 使用，对应三个服务：

| 服务 | 作用 | 关键变量 |
|---|---|---|
| `train-api` | FastAPI（常驻） | 恒用 CPU 轮子镜像 `yolo-manager/train:cpu`，`/model` 挂载 `model-data` 卷 |
| `train-worker-cpu` | Celery worker（profile `cpu`） | `DEVICE=cpu`，`TRAIN_WORKERS` 默认 4 |
| `train-worker-gpu` | Celery worker（profile `gpu`） | `DEVICE=cuda`，`TRAIN_WORKERS` 默认 8，带 nvidia 设备预约（`GPU_COUNT` 控制卡数） |

要点：

- 上表所有配置（MySQL/Redis/S3/目录/默认参数）由 compose 以环境变量从 `.env` 注入；`.env` 由脚本生成，不要手工改 compose 里的口令。改配置有两种方式：跑 `./deploy.sh` / `.\deploy.ps1`（英文提示版 `deploy_en.sh` / `deploy_en.ps1`）走交互式引导（可选“自建”还是“用已有实例”），或直接改脚本顶部【配置区】后执行 `./deploy.sh env` / `.\deploy.ps1 -Action env -Guide none`（全走 docker 镜像，不提问）。本机已装 MySQL / Redis 时，用 `-MysqlExposePort 13306 -RedisExposePort 16379`（sh 则用同名环境变量前置）把自建容器映射到宿主机的端口避开占用，不影响容器内互联。选“用已有实例”时 `COMPOSE_PROFILES` 里不会出现对应的 `builtin-*`，那个容器就不会启动也不会占宿主机端口，详见 [根 README 的 profiles 说明](../README.md#profiles-与外部组件)。
- 镜像里的 torch 类型由构建参数 `TORCH_INDEX_URL_CPU` / `TORCH_INDEX_URL_GPU` 决定（后者由 `CUDA_TAG` 拼接），`INSTALL_YOLO=false` 则不装 torch/ultralytics，只能跑管理端流程。
- `train-api` 与 worker **必须共享 `model-data` 卷**（WORK_DIR / YOLO_PROJECT_DIR / BASE_MODELS_DIR 都在 `/model` 下），否则 worker 取不到 API 准备的数据。
- 容器是 Linux，Celery 用默认 prefork 池即可，**不需要 `--pool=solo`**（那是 Windows 本机开发的限制）。
- `TRAIN_SHM_SIZE`（默认 8g）对应 `shm_size`；DataLoader 多进程报 “pipe is already closed / bus error” 先把它调大。

## 7. 训练任务执行链路

1. `POST /api/train/start` → `train_task.delay(task_id, dataset_id, model_base, epochs, batch_size, imgsz, callback_url, callback_token, base_model_type, base_version_id, expected_version, storage_prefix)`，任务名固定 `yolo.train_task`（`max_retries=0`）；本地记录 `async_result` 后用 `db.mark_queued_if_created` 把状态置为 `QUEUED`（原子条件更新，仅 `CREATED` 可写，避免把 worker 已写的 `RUNNING` 覆盖回排队中）
2. worker 双层去重：先查 MySQL 任务状态，再抢 Redis 执行锁 `train:running:{task_id}`（SET NX，训练期间由后台线程续租），重复投递直接跳过，避免并发互删数据目录
3. 从 S3 并发下载数据集图片与 `labels/` 标注 → 生成 `data.yaml`
4. 执行 Ultralytics 训练（加载权重前先注入 YOLOv13 兼容层），输出落在 `YOLO_PROJECT_DIR`；每 epoch 解析 `results.csv` 并：
   - Redis 写 `train:metrics:{task_id}`（指标库，TTL 7 天）
   - MySQL 更新任务状态与进度（`update_task_status`）；逐 epoch 指标不写 MySQL
5. 训练期间轮询 `train:cancel:{task_id}`，命中则中断
6. 归档 `best.pt` / `last.pt` / `results.csv` / `args.yaml` 到 `yolo-models` 桶：目录优先用管理后端下发的 `storage_prefix`（`task-{taskId}`），未下发时回退版本号；版本号跨系列可重复，靠唯一前缀避免互相覆盖
7. 回调管理后端 `POST /api/train/callback` 注册模型版本

## 8. 注意事项与现状

1. **Redis 协议兼容**：所有 Redis 客户端显式 `protocol=2`（RESP2），以兼容旧版服务端（如 Windows Redis 3.2、不支持 `HELLO` 命令）。
2. **阻塞接口一律用同步 `def`**：`train_start` 等接口若写成 `async def`，阻塞式 `delay()`/broker 调用会卡死事件循环，导致包括 `/api/health` 在内的整个服务无响应。
3. **Celery visibility_timeout**：broker 默认 1 小时，`acks_late` 下超长训练可能被重复投递，相关取舍写在 `worker.py` 注释中，改并发/时长前先看。
4. **TRAIN_WORKERS > 0 在 prefork 子进程中需要临时解除 daemon 限制**（见 `training.py`），Windows 上请保持 0。
5. **YOLOv13 自训练权重兼容**：`utils/yolov13_compat.py` 内置 YOLOv13（iMoonLab/yolov13 fork）的 12 个自定义模块（DSC3k2 / DSC3k / DSBottleneck / DSConv / HyperACE / C3AH / AdaHGConv / AdaHGComputation / AdaHyperedgeGen / FuseModule / DownsampleConv / FullPAD_Tunnel），在 `YOLO(model_path)` 之前注入 `ultralytics.nn.modules.block` / `conv` 命名空间，否则 pickle 反序列化报 `has no attribute 'DSC3k2'`。官方与 fork 同名但子模块布局不同的 `AAttn` 用一个自适应子类替换（两种权重都能加载），`ensure_yolov13_compat()` 幂等且不修改 site-packages，升级 ultralytics 不受影响。接入点：`services/inference.py` 与 `services/training.py` 的训练执行函数。
6. **版本号与存储路径由后端下发**：`expected_version` / `storage_prefix` 均来自管理后端（系列内去重后的版本号 + 唯一存储前缀），Python 侧不再自行推导目录，只做缺省回退，以兼容未升级的调用方。
7. 官方权重清单只收「检测权重」：`DETECT_RE = ^yolo(v?)(\d+)([nsmlx])\.pt$`，排除 `-seg` / `-pose` / `-cls` 等多任务变体，并统一映射成带 `v` 的平台命名（`yolov11n`）。GitHub 下载带 `https://gh-proxy.com/` 镜像前缀，纯离线环境请改用 `BASE_MODELS_DIR` 手工放置权重。
8. CORS 全开（`allow_origins=["*"]`），服务只适合内网或受网关保护的环境。
9. 容器化部署已就绪：本模块有 [Dockerfile](Dockerfile)，编排与 CPU/GPU 切换见上一节与 [根 README 的“一键部署（Docker）”](../README.md#一键部署docker推荐)。
10. 日志写入 `logs/`（`logging_utils.py` 配置），临时数据写入 `work/`，两者均已被根 `.gitignore` 忽略。
