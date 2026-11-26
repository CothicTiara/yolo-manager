# YOLO 视觉识别模型管理平台

[English](README_EN.md)

一站式目标检测模型训练与管理系统，覆盖图片上传、在线标注、数据集管理、自动训练、模型版本控制与推理测试全流程。平台采用 Vue 3 前端 + Spring Boot 管理后端 + Python FastAPI/Celery 训练服务，结合 S3 对象存储，实现从数据到模型上线的闭环管理，降低视觉 AI 应用门槛。

## 组件文档

| 组件 | 说明文档 | 技术栈 | 默认端口 | 职责 |
|---|---|---|---|---|
| 前端 | [vue-subject/README.md](vue-subject/README.md) | Vue 3 + Element Plus + ECharts + Vite 5 | 5173 | 数据集、标注、训练、模型、基础模型、使用指南 7 个页面（中英双语，默认中文） |
| 管理后端 | [java-manger-subject/README.md](java-manger-subject/README.md) | Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 + JDK 17 | 8080（prod 12391） | 业务与数据管理、任务编排、模型版本、对外识别接口 |
| 训练/推理服务 | [python-yolo-subject/README.md](python-yolo-subject/README.md) | FastAPI + Celery 5.4 + Ultralytics | 8000（prod 12390） | 训练执行、指标回传、产物归档、推理 |
| 在线接口文档 | [管理后端第 4 节](java-manger-subject/README.md#4-接口一览) | SpringDoc / Swagger UI | `http://localhost:8080/swagger-ui.html` | 管理端全部 REST 接口；训练服务另有 `http://localhost:8000/docs` |

三份组件文档都配有英文版（`README_EN.md`），部署脚本也分中文提示版与英文提示版（行为完全一致，共用同一份 `.env`）。

## 架构总览

```
          浏览器
             │
             ▼
   ┌───────────────────┐        /api、/storage 代理
   │  vue-subject      │ 5173
   │  Vue 3 + Element+ │
   └─────────┬─────────┘
             │ HTTP (Result: code/message/data)
             ▼
   ┌───────────────────┐        ┌────────────────────┐
   │ java-manger-      │ 8080   │ MySQL 8            │ 9 张业务表
   │ subject           ├───────►│ yolo_manager       │ (dataset / annotation /
   │ Spring Boot       │        └────────────────────┘  model_version / …)
   └───┬───────────┬───┘
       │           │        ┌────────────────────┐
       │ REST 投递 │◄───────►│ Redis              │ db5 指标 train:metrics:{id}
       │ 回调注册  │  HTTP   │ broker db1/result  │ db1 train:queue（redis 模式）
       │           │  Redis │ db2 / 队列          │
       ▼           ▼        └────────────────────┘
   ┌───────────────────┐
   │ python-yolo-      │ 8000        ┌──────────────────┐
   │ subject (FastAPI) │────────────►│ RustFS / MinIO   │ yolo-datasets
   ├───────────────────┤   S3 SDK    │ (S3 兼容存储)    │ yolo-models
   │ Celery worker     │             └──────────────────┘ yolo-tests
   │ yolo.train_task   │
   └───────────────────┘
```

数据流向：前端只与管理后端通信；管理后端负责业务与数据库读写，并把训练/推理请求转发给 Python 服务；Python 服务通过 S3 读写图片与权重，把实时指标写 Redis、把终态写 MySQL，训练完成后回调管理后端注册模型版本。

## 核心业务流程

| 步骤 | 操作 | 涉及组件 |
|---|---|---|
| 1 创建数据集 | 填写名称（≤100）/描述/训练集比例 `trainRatio`（默认 0.8），得到 `dataset` 记录 | 前端 → Java |
| 2 导入数据 | 批量上传图片，或上传 zip（YOLO 标注包 / COCO JSON / 纯图片包），后台异步处理并轮询进度 | 前端 → Java → S3 |
| 3 在线标注 | 画布绘制边界框并关联标签，坐标按原图归一化（0~1，6 位小数） | 前端 → Java → MySQL |
| 4 **导出标注** | 把结构化标注转成 YOLO `labels/{imageId}.txt` 写入存储 | Java → S3 |
| 5 训练 | 选择基底模型（BASE 新训 / TRAINED 继续训练）与 epochs/batch/imgsz → 投递 Celery → 实时曲线 → 回调注册版本 | Java → Python → Celery |
| 6 上线与调用 | 激活某版本为默认模型；平台内「调用测试」或第三方直接调 `POST /api/inference/detect` | 前端 → Java → Python |

> 关键提醒：**训练读取的是「导出到存储」的标注文件**，在线标注完成后必须先执行第 4 步「导出标注」，否则训练会因找不到标注而失败。站内同样的一份操作手册见前端「使用指南」页（`/guide`）。

## 仓库结构

```
yolo-manager/
├── docker-compose.yml       # 一键部署编排：MySQL + Redis + RustFS + 三个应用组件 + worker
├── deploy.sh                # Linux / macOS 一键部署脚本（交互式引导 + 顶部【配置区】默认值，中文提示）
├── deploy_en.sh             # 同一脚本的英文提示版（行为与产物完全相同）
├── deploy.ps1               # Windows（Docker Desktop）等价脚本，中文提示
├── deploy_en.ps1            # 同一 PowerShell 脚本的英文提示版
├── vue-subject/             # 前端（含 Dockerfile + nginx.conf，详见其 README）
├── java-manger-subject/     # Spring Boot 管理后端（含 Dockerfile；建表脚本在 src/main/resources/db/）
├── python-yolo-subject/     # FastAPI + Celery 训练与推理服务（含 Dockerfile，详见其 README）
├── .gitignore               # 三个组件的忽略规则统一写在这里（Java/Python/前端产物、日志、.env）
├── LICENSE
├── README.md                # 本文件（中文）
└── README_EN.md             # 英文版
```

## 外部依赖

| 依赖 | 版本要求 | 用途 |
|---|---|---|
| MySQL | 8.0+ | 业务库 `yolo_manager`（Java 与 Python 共用）；建表脚本见 [java-manger-subject 第 6 节](java-manger-subject/README.md)，文件：`src/main/resources/db/schema.sql`（存量库可单独执行 `db/patch-2026-11-26-model-series.sql`） |
| Redis | 建议 5+（旧版 3.2 已做 RESP2 兼容） | 训练指标实时缓存、Celery broker/result、可选任务队列 |
| 对象存储 | RustFS / MinIO 等 S3 兼容服务 | 三个桶：`yolo-datasets`、`yolo-models`、`yolo-tests`；Java 侧可切 `local` 磁盘模式，免依赖 |
| Docker | Engine ≥ 20.10 + Compose v2（或 Docker Desktop） | 一键部署使用；GPU 模式另需 nvidia-container-toolkit |
| JDK / Maven | 17 / ≥ 3.8（无 mvnw） | 本地构建管理后端（Docker 构建走多阶段镜像，宿主机无需安装） |
| Python | 3.10+（训练需另装 torch/ultralytics） | 训练与推理服务 |
| Node.js | 18+（Vite 5） | 前端开发与构建 |
| GPU | 可选 | 仅训练需要；未装 torch 时管理端流程仍可联调 |

## 一键部署（Docker，推荐）

一条命令拉起全部依赖与应用：MySQL 8.0、Redis 7、RustFS（S3 兼容对象存储）、管理后端、训练/推理服务、Celery worker、nginx 前端。数据全部落在 named volume（`mysql-data` / `redis-data` / `model-data` / `rustfs-data-0..3`），`down` 不会丢数据。

**默认三个基础组件都由 docker 镜像拉起；如果你已经有在跑的 MySQL / Redis / 对象存储，引导里选「用已有实例」即可，对应的容器不会启动、也不会占用宿主机端口。** 两个脚本顶部都有一段【配置区】，它就是引导里每个问题的默认值 —— 直接回车等于接受配置区；不想被提问就改完配置区跑 `env` / `cpu` / `gpu`。脚本把结果写成同目录 `.env`（已 gitignore），`docker-compose.yml` 只负责读取，不要手工改 compose 里的口令。

```bash
# Linux / macOS
./deploy.sh                 # 交互式引导：训练模式 + 每个组件自建/外部 + 端口与训练参数
./deploy.sh config          # 只做引导并生成 .env（不构建、不启动）
./deploy.sh env             # 不提问，用【配置区】值生成 .env 并校验编排（建议先跑一次）
./deploy.sh cpu             # 不提问，全部组件走 docker 镜像，CPU 训练
./deploy.sh gpu             # 不提问，全部组件走 docker 镜像，GPU 训练
./deploy.sh status|logs|down|restart|schema
vim deploy.sh               # 改【配置区】默认值（改完可用 ./deploy.sh config 重新生成）

# 英文提示版：与 deploy.sh 完全等价，写同一份 .env
./deploy_en.sh cpu
MYSQL_EXPOSE_PORT=13306 REDIS_EXPOSE_PORT=16379 ./deploy_en.sh env
```

```powershell
# Windows（Docker Desktop，建议 WSL 2 后端）
powershell -ExecutionPolicy Bypass -File .\deploy.ps1              # 交互式引导
.\deploy.ps1 -Action config                                       # 只引导并生成 .env
.\deploy.ps1 -Action env -Guide none                              # 不提问，只生成 .env 并校验
.\deploy.ps1 -Mode cpu                                            # 不提问，全部走 docker 镜像
.\deploy.ps1 -Mode gpu -CudaTag cu121 -GpuCount 1                  # GPU，指定轮子与卡数
.\deploy.ps1 -Action status                                       # status / logs / down / restart / schema

# 英文提示版（.\deploy_en.ps1）参数名与行为完全一致，同样写 .\deploy.ps1 用的那一份 .env
.\deploy_en.ps1 -Mode cpu -Action env

# 不提问地接入外部组件（CI / 重复部署）：给了 -External* 就视为使用已有实例
.\deploy.ps1 -Mode cpu -Action env -ExternalMysql 10.0.0.9 -MysqlDatabase glm `
             -MysqlUser glm -MysqlPassword 'pwd' -AutoDdl `
             -ExternalRedis 10.0.0.9 -ExternalStorage http://10.0.0.9:9000 -AutoBucket

# 本机已装 MySQL / Redis 时，把自建容器映射到宿主机的端口避开占用（容器内部仍用 3306/6379 互联）
.\deploy.ps1 -Mode cpu -MysqlExposePort 13306 -RedisExposePort 16379
```

### 引导会问什么

| 步骤 | 默认（全部走 docker） | 选「用已有实例」时需要补填 |
|---|---|---|
| 训练加速 | `cpu`，起 `train-worker-cpu` | 选 `gpu` 则起 `train-worker-gpu`（另问 CUDA 后缀、卡数） |
| MySQL | 自建，库/账号由镜像创建，自动执行 `db/*.sql`（patch + schema，均幂等） | 地址、端口、库名、应用账号口令；再问一次「要不要自动建表」（要建则再填有建表权限的账号） |
| Redis | 自建，`--requirepass` 起服务 | 地址、端口、口令（可空） |
| 对象存储 | 自建 RustFS，自动建三个桶 | endpoint、access/secret key、region、是否 HTTPS；再问「要不要自动建桶」 |
| 端口 | 前端 80、后端 8080、训练 8000 | 自建组件映射到宿主机的端口：`WEB_PORT` / `JAVA_PORT` / `PY_PORT`，以及 `MYSQL_EXPOSE_PORT` / `REDIS_EXPOSE_PORT` / `RUSTFS_API_PORT` / `RUSTFS_CONSOLE_PORT`（本机已装 MySQL、Redis 或 MinIO 时用它们避让，不影响容器内部互联） |
| 训练参数 | 装 torch、DataLoader 子进程、batch_size、共享内存、PyPI 源 | — |
| 跨机与回调 | 容器内部互联，回调不校验 | 训练服务地址、回调基地址、token、JVM 参数 |

引导有两档：`1=完整引导`（含 Redis 库编号、桶寻址方式、PyPI 源、跨机地址等细节项）、`2=快速引导`（只问训练模式、三个组件自建还是外部、关键口令与端口、训练核心参数）。所有回答都会做非空/端口范围/endpoint 协议校验，非法输入会重新追问而不会写坏 `.env`。

### profiles 与外部组件

`.env` 里的 `COMPOSE_PROFILES` 决定哪些服务真正启用，所以「全部内置」和「全部外部」的容器清单差别很大：

| profile | 启用的服务 | 何时有 |
|---|---|---|
| `cpu` / `gpu` | 对应的 `train-worker-*` | 必选其一 |
| `builtin-mysql` | `mysql` + `db-init` | 选择自建 MySQL |
| `builtin-redis` | `redis` | 选择自建 Redis |
| `builtin-storage` | `rustfs-init` + `rustfs` + `bucket-init` | 选择自建 RustFS |
| `auto-ddl` | `db-init` | 用外部 MySQL 但希望脚本代为建表 |
| `auto-bucket` | `bucket-init` | 用外部对象存储但希望自动建桶 |

例：自建 Redis + 外部 MySQL（库已建好）+ 外部 MinIO → `COMPOSE_PROFILES=cpu,builtin-redis`。切换模式或切换组件来源后重跑脚本即可，`up` 带 `--remove-orphans`，不再需要的容器会被删掉，named volume 数据保留。

接入外部组件时的四个硬性要求（引导里都会打印提醒）：

1. **地址要应用容器可达**：`127.0.0.1` / `localhost` 指向的是容器自己，写宿主机 IP 或宿主机在容器网络里的地址。
2. **MySQL 认证插件**：账号若用 `caching_sha2_password`，Python 侧（PyMySQL 未装 cryptography）会连不上；改成 `mysql_native_password`，或在镜像里补装 cryptography。库字符集需 `utf8mb4`。
3. **Redis 指标库编号两端一致**：`REDIS_METRICS_DB` 同时注入 Java 的 `spring.data.redis.database` 与 Python 的 `REDIS_METRICS_DB`，只要用引导/配置区就不会错位；外部 Redis 要允许使用该编号的库且可写。
4. **对象存储要支持 path-style 寻址**：Python 侧固定 `addressing_style=path`（MinIO/RustFS 支持）；只支持 virtual-host 的云服务需换成兼容 path-style 的接入点。endpoint 还必须带协议（`http://` 或 `https://`）。

### 启动顺序为什么由脚本负责

被 profile 关掉的服务如果出现在别的服务的 `depends_on` 里，compose 会直接报 `depends on undefined service` 并拒绝启动 —— 而外部组件场景下 `mysql` / `redis` / `rustfs` 都可能被关掉。所以 `docker-compose.yml` 只在「恒定启用」的应用服务之间写 `depends_on`（`web → manager`、`train-worker-* → train-api`），基础设施与初始化任务由脚本分三个阶段完成：

1. `docker compose up -d --wait`：只起本次启用的内置组件，并等健康检查通过；
2. `docker compose run --rm db-init` / `bucket-init`：建表与建桶，跑完即退出；
3. `docker compose up -d --remove-orphans`：起 manager / train-api / train-worker-* / web。

手工执行 `docker compose up -d` 在「全部内置」时同样可用：`db-init` 自带 MySQL 就绪轮询，不会因抢跑而失败；其余服务首启可能因依赖未就绪而重启几轮（各服务都是 `restart: unless-stopped`，会自行收敛），推荐仍用脚本。

服务与端口（都可在配置区/引导里改）：

| 入口 | 默认地址 | 说明 |
|---|---|---|
| 前端 | `http://<宿主机IP>:80` | nginx 容器，`/api`、`/storage`、swagger 均反代到 `manager:8080` |
| 管理后端 | `http://<宿主机IP>:8080/swagger-ui.html` | Spring Boot |
| 训练服务 | `http://<宿主机IP>:8000/docs` | FastAPI |
| RustFS 控制台 | `http://<宿主机IP>:9001` | 用引导里填的 access/secret key 登录；仅自建时映射 |
| MySQL / Redis | `<宿主机IP>:3306` / `:6379` | 仅自建时映射；应用容器之间走服务名 `mysql` / `redis` |

CPU / GPU 到底区分了什么：

| 差异点 | cpu | gpu |
|---|---|---|
| 启动的 worker | `train-worker-cpu` | `train-worker-gpu`（带 nvidia 设备预约，`GPU_COUNT` 控制卡数） |
| profile 机制 | `COMPOSE_PROFILES` 写入 `.env`，切换模式重跑脚本即可（`up` 带 `--remove-orphans`，会换掉另一个 worker） |
| torch 轮子 | `TORCH_INDEX_URL_CPU`（whl/cpu） | `TORCH_INDEX_URL_GPU`（由 `CUDA_TAG` 拼接，默认 cu124） |
| `DEVICE` | cpu | cuda（仅传给训练；train-api 恒用 CPU 轮子） |
| `TRAIN_WORKERS` / `DEFAULT_BATCH_SIZE` | 4 / 8 | 8 / 16 |
| 前置检查 | 只查 docker | 额外查 `nvidia-smi` 与 docker 的 nvidia 运行时，不满足直接终止 |

其他实现细节：

1. **建表**：`db-init` 容器先轮询等 MySQL 可用（`mysqladmin ping`，最多 180s；裸跑 `docker compose up -d` 时没有脚本的 `--wait` 分段，不等就会因连不上而退出），再按字母序执行 `java-manger-subject/src/main/resources/db/*.sql`（`patch-2026-11-26-model-series.sql` 在前、`schema.sql` 在后，全部 `IF NOT EXISTS` / `information_schema` 守卫，可重复跑），并把脚本里写死的 `yolo_manager` 自动换成配置区的 `MYSQL_DATABASE`；连接地址与账号也取自配置，所以对外部 MySQL 一样能用（`auto-ddl`）。
2. **建桶**：`bucket-init` 用 `minio/mc` 建三个桶（`mc mb --ignore-existing`），自己重试直到对象存储可用，不依赖健康检查；endpoint 来自配置，外部存储加 `auto-bucket` 即可复用。另外 Python 侧 `storage.py` 在 `head_bucket` 404 时也会自动建桶，这一步属兜底。
3. **RustFS 权限**：官方镜像以 UID 10001 运行，named volume 首次创建属 root，故先用 `rustfs-init` 容器 `chown`。
4. **MySQL 认证**：自建实例启动参数强制 `mysql_native_password`，否则 PyMySQL（未装 cryptography）无法完成 `caching_sha2_password` 认证。
5. **图片链路**：前端统一走 Java 的 `/api/storage/{bucket}/{key}` 代理读对象存储，浏览器不需要直连 RustFS，所以 S3 endpoint 用容器内网域名 `http://rustfs:9000` 即可。
6. **内网/离线**：配置区可换 `MYSQL_IMAGE` / `REDIS_IMAGE` / `ALPINE_IMAGE` / `RUSTFS_IMAGE` / `MC_IMAGE` 与 `PIP_INDEX_URL`；基础权重可预先放进 `model-data` 卷的 `base_models` 目录（避免训练时联网下载 GitHub 权重）。
7. **跨机部署训练**（把 worker 放到 GPU 机上）：引导第 7 步把管理后端访问训练服务的地址改成 `http://<训练机IP>:<PY_PORT>`、回调基地址改成 `http://<管理机IP>:<JAVA_PORT>`，否则训练完的回调打不回来、模型版本不会注册。

## 快速开始（本地，不走 Docker）

```bash
# 0. 先准备 MySQL（可用 schema.sql 建库建表）、Redis，并确定对象存储（或把 yolo.storage.type 改为 local）

# 1. 管理后端
cd java-manger-subject
mvn clean package -DskipTests
java -jar target/yolo-manager.jar                 # http://localhost:8080，Swagger UI /swagger-ui.html

# 2. 训练服务（FastAPI + Celery 两个进程）
cd ../python-yolo-subject
cp .env.example .env                              # 修改数据库 / Redis / S3 / 目录
pip install -r requirements.txt                   # GPU 机器再放开 ultralytics / torch
uvicorn app.main:app --host 0.0.0.0 --port 8000   # 终端 A
celery -A app.worker:celery_app worker --loglevel=info --pool=solo   # 终端 B（Windows 必须 --pool=solo）

# 3. 前端
cd ../vue-subject
npm install
npm run dev                                       # http://localhost:5173（端口被占会自动递增）
```

浏览器打开 `http://localhost:5173`，按「数据集 → 导入 → 标注 → 导出 → 训练 → 激活 → 调用」顺序走一遍即可跑通全链路。

## 跨组件一致性检查清单

改配置时最容易踩的坑都是「两端不一致」，逐项核对：

| 项目 | 管理后端 | 训练服务 | 必须一致的原因 |
|---|---|---|---|
| 指标库 | `spring.data.redis.database` = 5 | `REDIS_METRICS_DB` = 5 | 前端训练曲线靠 Java 读 Redis `train:metrics:{taskId}` |
| 桶名 | `yolo.storage.s3.bucket-*` | `S3_BUCKET_*` | 训练要从同一桶读图片与 `labels/` |
| 回调地址 | `yolo.callback.base-url`（环境变量 `YOLO_CALLBACK_BASE_URL`） | 由 Java 随任务下发 `callbackUrl` | 跨机部署时 Python 要能访问到 Java，否则模型版本不会注册 |
| 回调 token | `yolo.callback.token` | `CALLBACK_TOKEN` | 不匹配时回调被拒（Java 侧留空即不校验） |
| 存储模式 | `yolo.storage.type` | `STORAGE_TYPE` | 一端 `s3` 一端 `local` 会出现图片能上传但训练取不到 |
| 接口前缀 | `/api/**` | — | 前端 Vite 代理指向 8080（本地开发）；Docker 部署时由 `vue-subject/nginx.conf` 反代，改后端端口不需要改前端代码 |

## 部署现状与已知限制

1. 容器化编排已齐备：根目录 `docker-compose.yml` + `deploy.sh` / `deploy.ps1`（交互式引导，默认全部组件走 docker 镜像，也可指向已有 MySQL / Redis / 对象存储），三个组件各自带 `Dockerfile`（前端另有 `nginx.conf`），详见上一节。口令一律从 `.env` 读取，不要写回 compose 文件。
2. Java 侧提供 `application-prod.yml` profile（`--spring.profiles.active=prod`），仅适用于不用 Docker 的手工部署；Docker 部署走的是 `application.yml` + 环境变量覆盖（Spring Boot relaxed binding）。
3. `application.yml`、`.env.example`、`.env` 中存在真实内网地址与数据库/对象存储口令。开源或对外交付前请替换为占位值，并把连接信息改成环境变量注入（Docker 部署下就是改配置区，环境变量仍会覆盖 yml 里的硬编码值）；本 README 与组件 README 均不复制明文口令。
4. **全平台没有登录与鉴权**，Java 与 Python 均放开 CORS，`/api/inference/*` 可被任意调用 —— 只适合内网使用或置于网关/反向代理之后。
5. 大文件能力：Java 侧 `multipart` 上限 5120MB、Tomcat 连接超时 900s，导入/导出为后台线程池任务并对单个数据集加互斥锁；前端通过进度接口轮询。
6. `GET /api/train/{id}/metrics` 读的是 Redis 缓存（TTL 7 天），过期后曲线为空属预期：**逐 epoch 指标不落 MySQL**，长期留存需靠训练时归档到 S3 的 `results.csv`（回调字段 `resultsCsvKey`）重建；最终 mAP@50 / mAP@50-95 存在 `model_version` 表。
7. GPU 模式只给训练 worker 挂了显卡；`train-api` 里的在线识别（模型测试、`/api/inference/detect`）仍走 CPU。单图推理对 CPU 来说压力不大，确实需要 GPU 推理时按 `docker-compose.yml` 里 `train-worker-gpu` 上方的注释把 `deploy.resources` 片段复制到 `train-api`。

## 许可

见 [LICENSE](LICENSE)。
