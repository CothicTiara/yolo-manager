#!/usr/bin/env bash
# =============================================================================
# YOLO 视觉识别模型管理平台 —— 一键部署（Linux / macOS，Docker Compose）
#
#   ./deploy.sh                # 交互式引导（推荐首次使用）
#   ./deploy.sh cpu            # 不提问，全部组件走 docker 镜像，CPU 训练
#   ./deploy.sh gpu            # 不提问，全部组件走 docker 镜像，GPU 训练
#   ./deploy.sh config         # 只做引导并生成 .env，不构建不启动
#   ./deploy.sh env            # 用下面【配置区】的值生成 .env 并校验编排
#   ./deploy.sh status|logs|down|restart|schema
#
# 脚本把配置写进同目录 .env（已 gitignore），docker-compose.yml 只负责读取。
# 重复执行是安全的：镜像走缓存，建表与建桶都是 IF NOT EXISTS / --ignore-existing。
# 组件既可以全部由 docker 拉起（默认），也可以在引导里指向你已部署好的
# MySQL / Redis / 对象存储；外部组件对应的容器会自动停用。
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# =============================================================================
# 【配置区】以下即为默认值；交互式引导会在它们的基础上让你逐项确认/覆盖。
# 想用「不提问」的方式部署，就直接改这里，然后执行 ./deploy.sh cpu / gpu / env。
# 注意：口令不要包含 $ 、空格或 “空格+#”，compose 解析 .env 时会出错。
# =============================================================================

# ---- 数据库 ----
BUILTIN_MYSQL="true"             # true=容器内自建 MySQL；false=使用已有实例
MYSQL_HOST="mysql"               # 自建时固定 mysql（容器网络内的服务名）
MYSQL_PORT="3306"                # 应用连接的端口
MYSQL_EXPOSE_PORT="${MYSQL_EXPOSE_PORT:-3306}"   # 自建时额外映射到宿主机的端口（便于客户端查看；本机已装 MySQL 时可前置该变量避让）
MYSQL_DATABASE="yolo_manager"    # 业务库名（schema.sql 里写死的库名会自动换成它）
MYSQL_USER="yolo"                # 应用账号（自建时由 MySQL 镜像自动创建）
MYSQL_PASSWORD="YoloApp#2026"    # 应用账号口令
MYSQL_ADMIN_USER="root"          # 建表使用的管理账号（自建时固定 root）
MYSQL_ADMIN_PASSWORD="YoloRoot#2026"
AUTO_DDL="false"                 # 用已有 MySQL 时是否仍自动执行 schema.sql（自建时总是执行）

# ---- Redis ----
BUILTIN_REDIS="true"
REDIS_HOST="redis"
REDIS_PORT="6379"
REDIS_EXPOSE_PORT="${REDIS_EXPOSE_PORT:-6379}"
REDIS_PASSWORD="YoloRedis#2026"  # 自建时不能留空：容器以 --requirepass 启动
REDIS_METRICS_DB="5"             # 指标库；Java 与 Python 由同一个变量注入，天然一致
REDIS_DB_BROKER="1"
REDIS_DB_RESULT="2"

# ---- 对象存储（S3 兼容）----
BUILTIN_STORAGE="true"
S3_ENDPOINT="http://rustfs:9000" # 必须是「应用容器能访问」的地址；浏览器不需要访问它
S3_ACCESS_KEY="yoloRustfsAdmin"  # 长度 5~20 字符，建议纯字母数字
S3_SECRET_KEY="YoloRustfs#2026"  # 长度 8~40 字符，建议纯字母数字
S3_REGION="us-east-1"
S3_USE_SSL="false"
S3_PATH_STYLE="true"             # Python 侧固定 path-style，外部存储需支持（MinIO/RustFS 支持）
S3_BUCKET_DATASETS="yolo-datasets"
S3_BUCKET_MODELS="yolo-models"
S3_BUCKET_TESTS="yolo-tests"
AUTO_BUCKET="false"              # 用已有对象存储时是否自动建缺失的桶（自建时总是执行）
RUSTFS_API_PORT="${RUSTFS_API_PORT:-9000}"       # 以下五项仅自建 RustFS 时生效
RUSTFS_CONSOLE_PORT="${RUSTFS_CONSOLE_PORT:-9001}"
RUSTFS_IMAGE="rustfs/rustfs:latest"   # 需要固定版本就改成如 rustfs/rustfs:1.0.4
# 单机部署时四个数据卷落在同一块设备上，RustFS 的磁盘校验会直接拒启；多盘真分布式部署改回 false
RUSTFS_BYPASS_DISK_CHECK="${RUSTFS_BYPASS_DISK_CHECK:-true}"

# ---- 应用端口 ----
WEB_PORT="80"                    # 前端入口（浏览器访问这个）
JAVA_PORT="8080"                 # 管理后端
PY_PORT="8000"                   # 训练/推理服务

# ---- 训练与推理参数 ----
TRAIN_MODE_DEFAULT="cpu"         # cpu | gpu
INSTALL_YOLO="true"              # false 则镜像不装 torch/ultralytics（只能跑管理端流程）
CUDA_TAG="cu124"                 # GPU 模式 torch 轮子后缀：cu118 / cu121 / cu124 / cu126
GPU_COUNT="all"                  # GPU 模式使用几张卡：all 或整数（如 1）
WORKER_CONCURRENCY="1"           # 单容器并发训练任务数，>1 需谨慎
TRAIN_WORKERS_CPU="4"            # CPU 模式 DataLoader 子进程数
TRAIN_WORKERS_GPU="8"            # GPU 模式 DataLoader 子进程数
DEFAULT_BATCH_SIZE_CPU="8"
DEFAULT_BATCH_SIZE_GPU="16"
TRAIN_SHM_SIZE="8g"              # 共享内存，DataLoader 多进程不足会报 broken pipe
PIP_INDEX_URL="https://pypi.org/simple"   # 国内网络建议改成 https://pypi.tuna.tsinghua.edu.cn/simple
DEFAULT_EPOCHS="100"
DEFAULT_IMGSZ="640"
DATASET_PREP_WORKERS="16"

# ---- 跨机部署与回调（单机保持默认即可）----
TRAIN_SERVICE_URL="http://train-api:8000"   # 训练放到别的机器时改成 http://<训练机IP>:<PY_PORT>
CALLBACK_BASE_URL="http://manager:8080"     # 跨机时改成 http://<管理机IP>:<JAVA_PORT>
TRAIN_DELIVERY_MODE="http"                  # http=调 /api/train/start；redis=写 train:queue
CALLBACK_TOKEN=""                           # 留空表示回调不校验；设置后两端必须一致

# ---- 镜像与 JVM（内网/离线环境按需替换镜像地址）----
MYSQL_IMAGE="mysql:8.0"
REDIS_IMAGE="redis:7-alpine"
ALPINE_IMAGE="alpine:3.20"        # 用于修正 RustFS 数据卷属主
MC_IMAGE="minio/mc:latest"        # 用于建桶
JAVA_OPTS="-Xms512m -Xmx1024m"

# =============================================================================
# 【配置区结束】以下内容一般不需要改动
# =============================================================================

ACTION="${1:-guide}"
GUIDE_LEVEL="full"     # full | quick | none
SKIP_DEPLOY="false"
TRAIN_MODE="$TRAIN_MODE_DEFAULT"
TRAIN_WORKERS=""
DEFAULT_BATCH_SIZE=""

usage() {
  echo "用法: $0 [guide|cpu|gpu|config|env|status|logs|down|restart|schema]" >&2
  echo "      guide(默认) 交互式引导    cpu/gpu 不提问、全部组件走 docker 镜像" >&2
}

case "$ACTION" in
  cpu|gpu)
    TRAIN_MODE="$ACTION"; GUIDE_LEVEL="none" ;;
  guide|config|env)
    [ "$ACTION" = "config" ] && SKIP_DEPLOY="true"
    [ "$ACTION" = "env" ] && GUIDE_LEVEL="none" && SKIP_DEPLOY="true" ;;
  status|logs|down|restart|schema)
    if [ ! -f .env ]; then
      echo "✗ 还没有 .env，请先执行 ./deploy.sh（引导）或 ./deploy.sh cpu" >&2
      exit 1
    fi
    case "$ACTION" in
      status)  exec docker compose ps ;;
      logs)    exec docker compose logs -f --tail=200 ;;
      down)    exec docker compose down ;;
      restart) exec docker compose restart ;;
      schema)
        echo "→ 执行 schema.sql（幂等）；目标库见 .env 的 MYSQL_HOST/MYSQL_DATABASE"
        echo "  若用的是已有 MySQL 且没勾选自动建表，请先 ./deploy.sh config 打开该选项"
        docker compose run --rm db-init
        exit 0 ;;
    esac ;;
  *) usage; exit 1 ;;
esac

# ---------- 引导用的输入函数 ----------
# ask <变量名> <提示> <默认值>：直接回车取默认值；非交互终端（管道/cron）自动取默认值
ask() {
  local __var="$1" __msg="$2" __def="${3:-}" __in=""
  ASK_EOF=0
  if [ "$GUIDE_LEVEL" = "none" ] || [ ! -t 0 ]; then
    printf -v "$__var" '%s' "$__def"
    return 0
  fi
  printf '  %s' "$__msg"
  if [ -n "$__def" ]; then printf ' [%s]' "$__def"; fi
  printf ': '
  IFS= read -r __in || ASK_EOF=1
  __in="${__in%$'\r'}"
  [ -z "$__in" ] && __in="$__def"
  if [ -n "${DEPLOY_DEBUG:-}" ]; then echo "    [debug] $__var = $__in" >&2; fi
  printf -v "$__var" '%s' "$__in"
}

# ask_required <变量名> <提示>：不允许为空，空则反复追问；输入流结束(EOF)则终止部署
ask_required() {
  local __var="$1" __msg="$2" __v=""
  while :; do
    ask __v "$__msg（不能留空）" ""
    if [ "${ASK_EOF:-0}" = "1" ]; then
      echo "✗ 输入已结束，必填项「$__msg」没有值，部署终止" >&2
      exit 1
    fi
    if [ -n "$__v" ]; then break; fi
    echo "    ! 该项不能为空"
  done
  printf -v "$__var" '%s' "$__v"
}

ask_port() {
  local __var="$1" __msg="$2" __def="$3" __v
  while :; do
    ask __v "$__msg" "$__def"
    case "$__v" in
      ''|*[!0-9]*) echo "    ! 请输入 1~65535 的数字"; continue ;;
    esac
    if [ "$__v" -lt 1 ] || [ "$__v" -gt 65535 ]; then echo "    ! 端口超出范围"; continue; fi
    break
  done
  printf -v "$__var" '%s' "$__v"
}

# ask_bool <变量名> <提示> <默认值 true|false>：接受 y/yes/n/no，结果存成 true/false
ask_bool() {
  local __var="$1" __msg="$2" __def="$3" __v="" __hint
  if [ "$GUIDE_LEVEL" = "none" ] || [ ! -t 0 ]; then
    printf -v "$__var" '%s' "$__def"
    return 0
  fi
  if [ "$__def" = "true" ]; then __hint="Y/n"; else __hint="y/N"; fi
  printf '  %s [%s]: ' "$__msg" "$__hint"
  IFS= read -r __v || __v=""
  __v="${__v%$'\r'}"
  case "$__v" in
    [yY]|[yY][eE][sS]) printf -v "$__var" '%s' "true" ;;
    [nN]|[nN][oO])     printf -v "$__var" '%s' "false" ;;
    "")                printf -v "$__var" '%s' "$__def" ;;
    *) echo "    ! 只接受 y / n，已保留默认（$([ "$__def" = true ] && echo 是 || echo 否)）"
       printf -v "$__var" '%s' "$__def" ;;
  esac
  if [ -n "${DEPLOY_DEBUG:-}" ]; then echo "    [debug] $__var = ${!__var}" >&2; fi
}

is_true() { [ "$1" = "true" ]; }
label_bool() { if is_true "$1"; then echo "docker 自建"; else echo "外部已有"; fi; }

# ---------- 交互式引导 ----------
if [ "$GUIDE_LEVEL" != "none" ]; then
cat <<'EOF'

=============================================================================
 YOLO 平台一键部署引导      （直接回车 = 保留中括号里的默认值；y/n 二选一）
 依赖：Docker Engine ≥ 20.10 + Compose v2；GPU 训练另需 nvidia-container-toolkit
 全程默认「组件都用 docker 镜像」，想指向已部署好的组件就在对应问题选 n
EOF
printf '=============================================================================\n'

  if [ "$GUIDE_LEVEL" = "full" ]; then
    echo
    ask _lvl "  引导粒度（1=完整引导，含外部组件细节；2=快速引导，全部走 docker 镜像）" "1"
    case "$_lvl" in
      2) GUIDE_LEVEL="quick" ;;
      *) GUIDE_LEVEL="full" ;;
    esac
  fi

  # ---- 1. 训练模式 ----
  echo
  echo "[1/7] 训练加速方式"
  while :; do
    ask TRAIN_MODE "  YOLO 训练使用 CPU 还是 GPU 加速（cpu/gpu）" "$TRAIN_MODE"
    case "$TRAIN_MODE" in cpu|gpu) break ;; *) echo "    ! 只能填 cpu 或 gpu" ;; esac
  done
  echo "    说明：GPU 只挂在训练 worker 容器上；在线识别仍走 CPU"

  # ---- 2. MySQL ----
  echo
  echo "[2/7] 数据库 MySQL（需要 MySQL 8.0+ 且字符集 utf8mb4）"
  ask_bool BUILTIN_MYSQL "  用 docker 镜像自建 MySQL 吗？（n=连你已有的实例）" "$BUILTIN_MYSQL"
  if is_true "$BUILTIN_MYSQL"; then
    ask MYSQL_DATABASE       "  业务库名（不存在时自动创建）" "$MYSQL_DATABASE"
    ask MYSQL_USER           "  应用账号（容器自动创建）" "$MYSQL_USER"
    ask MYSQL_PASSWORD       "  应用账号口令" "$MYSQL_PASSWORD"
    ask MYSQL_ADMIN_PASSWORD "  root 口令（建库建表用）" "$MYSQL_ADMIN_PASSWORD"
    ask_port MYSQL_EXPOSE_PORT "  映射到宿主机的端口（与其他 MySQL 冲突时改）" "$MYSQL_EXPOSE_PORT"
  else
    ask_required MYSQL_HOST  "  已有 MySQL 地址（应用容器要能访问到它，别写 127.0.0.1）"
    ask_port     MYSQL_PORT  "  端口" "3306"
    ask MYSQL_DATABASE       "  业务库名（需已存在或账号有建库权限）" "$MYSQL_DATABASE"
    ask MYSQL_USER           "  应用账号" "$MYSQL_USER"
    ask MYSQL_PASSWORD       "  应用账号口令" "$MYSQL_PASSWORD"
    ask_bool   AUTO_DDL      "  由脚本自动执行 schema.sql 建表吗？（表已建好选 n）" "$AUTO_DDL"
    if is_true "$AUTO_DDL"; then
      ask MYSQL_ADMIN_USER     "  有建表权限的账号" "$MYSQL_ADMIN_USER"
      ask MYSQL_ADMIN_PASSWORD "  该账号口令" "$MYSQL_ADMIN_PASSWORD"
    fi
    printf '    ! 该实例若用 caching_sha2_password 认证，Python 侧（PyMySQL 未装 cryptography）\n'
    printf '      可能连不上：把账号改成 mysql_native_password，或在镜像里补装 cryptography\n'
  fi

  # ---- 3. Redis ----
  echo
  echo "[3/7] Redis（Celery 队列 + 训练指标缓存）"
  ask_bool BUILTIN_REDIS "  用 docker 镜像自建 Redis 吗？" "$BUILTIN_REDIS"
  if is_true "$BUILTIN_REDIS"; then
    ask REDIS_PASSWORD   "  口令（自建时不能留空）" "$REDIS_PASSWORD"
    ask_port REDIS_EXPOSE_PORT "  映射到宿主机的端口" "$REDIS_EXPOSE_PORT"
  else
    ask_required REDIS_HOST "  已有 Redis 地址"
    ask_port     REDIS_PORT "  端口" "6379"
    ask REDIS_PASSWORD      "  口令（没有就回车留空）" ""
  fi
  ask_port REDIS_METRICS_DB "  训练指标所在库编号（Java/Python 共用此值，会自动一致）" "$REDIS_METRICS_DB"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_port REDIS_DB_BROKER "  Celery broker 库编号" "$REDIS_DB_BROKER"
    ask_port REDIS_DB_RESULT "  Celery result 库编号" "$REDIS_DB_RESULT"
  fi

  # ---- 4. 对象存储 ----
  echo
  echo "[4/7] 对象存储（S3 兼容，数据集/模型/测试三个桶）"
  ask_bool BUILTIN_STORAGE "  用 docker 镜像自建 RustFS 吗？（n=连你已有的 MinIO/RustFS 等）" "$BUILTIN_STORAGE"
  if is_true "$BUILTIN_STORAGE"; then
    ask S3_ACCESS_KEY "  access key（5~20 字符，建议纯字母数字）" "$S3_ACCESS_KEY"
    ask S3_SECRET_KEY "  secret key（8~40 字符，建议纯字母数字）" "$S3_SECRET_KEY"
    ask_port RUSTFS_API_PORT "    S3 API 映射到宿主机的端口（9000 被 MinIO 占用时改）" "$RUSTFS_API_PORT"
    ask_port RUSTFS_CONSOLE_PORT " 控制台映射到宿主机的端口" "$RUSTFS_CONSOLE_PORT"
    if [ "$GUIDE_LEVEL" = "full" ]; then
      ask RUSTFS_IMAGE "  RustFS 镜像（要钉版本可改成 rustfs/rustfs:1.0.4）" "$RUSTFS_IMAGE"
    fi
  else
    ask_required S3_ENDPOINT "  已有服务的 endpoint，需带协议（如 http://172.16.5.121:9000）"
    ask_required S3_ACCESS_KEY "  access key"
    ask_required S3_SECRET_KEY "  secret key"
    ask S3_REGION "  region" "$S3_REGION"
    ask_bool S3_USE_SSL "  通过 HTTPS 访问吗？" "$S3_USE_SSL"
    ask_bool AUTO_BUCKET "  由脚本自动创建缺失的桶吗？（桶已建好选 n）" "$AUTO_BUCKET"
    printf '    ! Python 侧固定使用 path-style 寻址（桶名在路径里），MinIO/RustFS 都支持；\n'
    printf '      只支持 virtual-host 风格的云服务需要换成兼容 path-style 的接入点\n'
  fi
  ask S3_BUCKET_DATASETS "  数据集桶名" "$S3_BUCKET_DATASETS"
  ask S3_BUCKET_MODELS   "  模型桶名" "$S3_BUCKET_MODELS"
  ask S3_BUCKET_TESTS    "  测试桶名" "$S3_BUCKET_TESTS"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_bool S3_PATH_STYLE "  Java 侧也使用 path-style 寻址吗？" "$S3_PATH_STYLE"
  fi

  # ---- 5. 应用端口 ----
  echo
  echo "[5/7] 应用对外端口"
  ask_port WEB_PORT  "  前端入口端口（浏览器访问 http://<IP>:端口）" "$WEB_PORT"
  ask_port JAVA_PORT "  管理后端端口" "$JAVA_PORT"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_port PY_PORT "  训练/推理服务端口" "$PY_PORT"
  fi

  # ---- 6. 训练参数 ----
  echo
  echo "[6/7] 训练相关参数"
  ask_bool INSTALL_YOLO "  镜像内安装 torch/ultralytics 吗？（n=只能跑管理端流程，构建快很多）" "$INSTALL_YOLO"
  if [ "$TRAIN_MODE" = "gpu" ]; then
    ask CUDA_TAG "  CUDA 轮子后缀（cu118/cu121/cu124/cu126）" "$CUDA_TAG"
    ask GPU_COUNT "  使用几张显卡（all 或数字）" "$GPU_COUNT"
    ask TRAIN_WORKERS "  DataLoader 子进程数" "$TRAIN_WORKERS_GPU"
    ask DEFAULT_BATCH_SIZE "  默认 batch_size" "$DEFAULT_BATCH_SIZE_GPU"
  else
    ask TRAIN_WORKERS "  DataLoader 子进程数" "$TRAIN_WORKERS_CPU"
    ask DEFAULT_BATCH_SIZE "  默认 batch_size" "$DEFAULT_BATCH_SIZE_CPU"
  fi
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask WORKER_CONCURRENCY "  单容器并发训练任务数" "$WORKER_CONCURRENCY"
    ask TRAIN_SHM_SIZE "  共享内存大小" "$TRAIN_SHM_SIZE"
    ask PIP_INDEX_URL "  PyPI 源（国内建议换成镜像站）" "$PIP_INDEX_URL"
  fi

  # ---- 7. 跨机与回调 ----
  echo
  echo "[7/7] 跨机部署与回调（单机直接回车用默认值）"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask TRAIN_SERVICE_URL "  管理后端访问训练服务的地址（跨机改成 http://<训练机IP>:端口）" "$TRAIN_SERVICE_URL"
    ask CALLBACK_BASE_URL "  训练服务回调管理后端的地址（跨机改成 http://<管理机IP>:端口）" "$CALLBACK_BASE_URL"
    ask CALLBACK_TOKEN    "  回调 token（留空=不校验；设置后两端必须一致）" "$CALLBACK_TOKEN"
    ask JAVA_OPTS         "  JVM 参数" "$JAVA_OPTS"
  else
    printf '    保持默认：容器内部互联（train-api:8000 / manager:8080），回调不校验\n'
  fi

  echo
  ask_bool SKIP_DEPLOY "  本次只生成 .env（不构建、不启动容器）吗？" "$SKIP_DEPLOY"
fi

# ---------- 归一化：内置组件用容器网络里的固定地址 ----------
if is_true "$BUILTIN_MYSQL"; then
  MYSQL_HOST="mysql"; MYSQL_PORT="3306"; MYSQL_ADMIN_USER="root"; AUTO_DDL="false"
fi
if is_true "$BUILTIN_REDIS"; then
  REDIS_HOST="redis"; REDIS_PORT="6379"
fi
if is_true "$BUILTIN_STORAGE"; then
  S3_ENDPOINT="http://rustfs:9000"; S3_USE_SSL="false"; S3_PATH_STYLE="true"; AUTO_BUCKET="false"
  MYSQL_EXPOSE_PORT="${MYSQL_EXPOSE_PORT:-3306}"
fi
REDIS_EXPOSE_PORT="${REDIS_EXPOSE_PORT:-6379}"
MYSQL_EXPOSE_PORT="${MYSQL_EXPOSE_PORT:-3306}"
RUSTFS_API_PORT="${RUSTFS_API_PORT:-9000}"
RUSTFS_CONSOLE_PORT="${RUSTFS_CONSOLE_PORT:-9001}"

case "$TRAIN_MODE" in gpu) : ;; *) TRAIN_MODE="cpu" ;; esac
if [ -z "$TRAIN_WORKERS" ]; then
  if [ "$TRAIN_MODE" = "gpu" ]; then
    TRAIN_WORKERS="$TRAIN_WORKERS_GPU"; DEFAULT_BATCH_SIZE="$DEFAULT_BATCH_SIZE_GPU"
  else
    TRAIN_WORKERS="$TRAIN_WORKERS_CPU"; DEFAULT_BATCH_SIZE="$DEFAULT_BATCH_SIZE_CPU"
  fi
fi
TORCH_INDEX_URL_CPU="https://download.pytorch.org/whl/cpu"
TORCH_INDEX_URL_GPU="https://download.pytorch.org/whl/${CUDA_TAG}"

# ---------- COMPOSE_PROFILES：训练模式 + 启用的内置组件 + 需要的初始化任务 ----------
PROFILES="$TRAIN_MODE"
if is_true "$BUILTIN_MYSQL"; then
  PROFILES="$PROFILES,builtin-mysql"
elif is_true "$AUTO_DDL"; then
  PROFILES="$PROFILES,auto-ddl"
fi
if is_true "$BUILTIN_REDIS"; then
  PROFILES="$PROFILES,builtin-redis"
fi
if is_true "$BUILTIN_STORAGE"; then
  PROFILES="$PROFILES,builtin-storage"
elif is_true "$AUTO_BUCKET"; then
  PROFILES="$PROFILES,auto-bucket"
fi

# ---------- 必填校验 ----------
check_required() {
  if [ -z "$2" ]; then echo "✗ $1 不能为空" >&2; exit 1; fi
}
check_required "MYSQL_DATABASE" "$MYSQL_DATABASE"
check_required "MYSQL_USER" "$MYSQL_USER"
check_required "MYSQL_PASSWORD" "$MYSQL_PASSWORD"
if ! is_true "$BUILTIN_MYSQL"; then check_required "MYSQL_HOST" "$MYSQL_HOST"; fi
if ! is_true "$BUILTIN_REDIS"; then check_required "REDIS_HOST" "$REDIS_HOST"; fi
if is_true "$BUILTIN_REDIS" && [ -z "$REDIS_PASSWORD" ]; then
  echo "✗ 自建 Redis 必须设置口令（compose 用 --requirepass 启动，留空会变成无密码服务）" >&2
  exit 1
fi
check_required "S3_ACCESS_KEY" "$S3_ACCESS_KEY"
check_required "S3_SECRET_KEY" "$S3_SECRET_KEY"
check_required "S3_BUCKET_DATASETS" "$S3_BUCKET_DATASETS"
check_required "S3_ENDPOINT" "$S3_ENDPOINT"
case "$S3_ENDPOINT" in
  http://*|https://*) : ;;
  *) echo "✗ S3_ENDPOINT 必须以 http:// 或 https:// 开头，当前为：$S3_ENDPOINT" >&2; exit 1 ;;
esac

# ---------- 前置检查 ----------
command -v docker >/dev/null 2>&1 || { echo "✗ 未找到 docker 命令，请先安装 Docker Engine（≥20.10）" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "✗ 无法连接 Docker 守护进程（服务未启动，或当前用户不在 docker 组）" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "✗ 缺少 Docker Compose v2，请安装 docker-compose-plugin" >&2; exit 1; }

if [ "$TRAIN_MODE" = "gpu" ]; then
  command -v nvidia-smi >/dev/null 2>&1 || { echo "✗ GPU 模式需要宿主机安装 NVIDIA 驱动（nvidia-smi）" >&2; exit 1; }
  nvidia-smi >/dev/null 2>&1 || { echo "✗ nvidia-smi 执行失败，请检查驱动状态" >&2; exit 1; }
  if ! docker info 2>/dev/null | grep -qi 'nvidia'; then
    echo "! 警告：docker info 中未发现 nvidia 运行时。请安装 nvidia-container-toolkit，"
    echo "  再执行 sudo systemctl restart docker 重试，否则 GPU worker 容器起不来。"
  fi
fi

# ---------- 生成 .env（docker compose 自动读取同目录该文件）----------
ENV_FILE=".env"
BACKUP_NOTE="（首次生成，无备份）"
if [ -f "$ENV_FILE" ]; then
  cp "$ENV_FILE" "$ENV_FILE.bak"
  BACKUP_NOTE="（原 .env 已备份为 .env.bak）"
fi

cat > "$ENV_FILE" <<EOF
# 由 deploy.sh 于 $(date '+%F %T') 生成；已被 .gitignore 忽略，勿提交
# 训练模式：$TRAIN_MODE
# MySQL：$(label_bool "$BUILTIN_MYSQL") ($MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE)
# Redis：$(label_bool "$BUILTIN_REDIS") ($REDIS_HOST:$REDIS_PORT，指标库 $REDIS_METRICS_DB)
# 对象存储：$(label_bool "$BUILTIN_STORAGE") ($S3_ENDPOINT)
# 改配置：./deploy.sh config（引导）或改 deploy.sh 顶部【配置区】后 ./deploy.sh env
COMPOSE_PROFILES=$PROFILES

BUILTIN_MYSQL=$BUILTIN_MYSQL
MYSQL_HOST=$MYSQL_HOST
MYSQL_PORT=$MYSQL_PORT
MYSQL_EXPOSE_PORT=$MYSQL_EXPOSE_PORT
MYSQL_DATABASE=$MYSQL_DATABASE
MYSQL_USER=$MYSQL_USER
MYSQL_PASSWORD=$MYSQL_PASSWORD
MYSQL_ADMIN_USER=$MYSQL_ADMIN_USER
MYSQL_ADMIN_PASSWORD=$MYSQL_ADMIN_PASSWORD

BUILTIN_REDIS=$BUILTIN_REDIS
REDIS_HOST=$REDIS_HOST
REDIS_PORT=$REDIS_PORT
REDIS_EXPOSE_PORT=$REDIS_EXPOSE_PORT
REDIS_PASSWORD=$REDIS_PASSWORD
REDIS_METRICS_DB=$REDIS_METRICS_DB
REDIS_DB_BROKER=$REDIS_DB_BROKER
REDIS_DB_RESULT=$REDIS_DB_RESULT

BUILTIN_STORAGE=$BUILTIN_STORAGE
S3_ENDPOINT=$S3_ENDPOINT
S3_ACCESS_KEY=$S3_ACCESS_KEY
S3_SECRET_KEY=$S3_SECRET_KEY
S3_REGION=$S3_REGION
S3_USE_SSL=$S3_USE_SSL
S3_PATH_STYLE=$S3_PATH_STYLE
S3_BUCKET_DATASETS=$S3_BUCKET_DATASETS
S3_BUCKET_MODELS=$S3_BUCKET_MODELS
S3_BUCKET_TESTS=$S3_BUCKET_TESTS
RUSTFS_API_PORT=$RUSTFS_API_PORT
RUSTFS_CONSOLE_PORT=$RUSTFS_CONSOLE_PORT
RUSTFS_IMAGE=$RUSTFS_IMAGE
RUSTFS_BYPASS_DISK_CHECK=$RUSTFS_BYPASS_DISK_CHECK

WEB_PORT=$WEB_PORT
JAVA_PORT=$JAVA_PORT
PY_PORT=$PY_PORT

INSTALL_YOLO=$INSTALL_YOLO
TORCH_INDEX_URL_CPU=$TORCH_INDEX_URL_CPU
TORCH_INDEX_URL_GPU=$TORCH_INDEX_URL_GPU
PIP_INDEX_URL=$PIP_INDEX_URL
TRAIN_WORKERS=$TRAIN_WORKERS
DEFAULT_BATCH_SIZE=$DEFAULT_BATCH_SIZE
TRAIN_SHM_SIZE=$TRAIN_SHM_SIZE
WORKER_CONCURRENCY=$WORKER_CONCURRENCY
GPU_COUNT=$GPU_COUNT
DEFAULT_EPOCHS=$DEFAULT_EPOCHS
DEFAULT_IMGSZ=$DEFAULT_IMGSZ
DATASET_PREP_WORKERS=$DATASET_PREP_WORKERS

TRAIN_SERVICE_URL=$TRAIN_SERVICE_URL
CALLBACK_BASE_URL=$CALLBACK_BASE_URL
TRAIN_DELIVERY_MODE=$TRAIN_DELIVERY_MODE
CALLBACK_TOKEN=$CALLBACK_TOKEN

MYSQL_IMAGE=$MYSQL_IMAGE
REDIS_IMAGE=$REDIS_IMAGE
ALPINE_IMAGE=$ALPINE_IMAGE
MC_IMAGE=$MC_IMAGE
JAVA_OPTS=$JAVA_OPTS
EOF

echo "→ 已写入 $ENV_FILE $BACKUP_NOTE"
echo "  profiles: $PROFILES"
echo "  训练模式 $TRAIN_MODE：DataLoader 子进程 $TRAIN_WORKERS，默认 batch_size $DEFAULT_BATCH_SIZE"

echo "→ 校验 docker-compose.yml 解析结果"
docker compose config --quiet || { echo "✗ 编排解析失败，请按上面的报错行修正配置" >&2; exit 1; }
echo "  ✓ 配置可解析"

if [ "$SKIP_DEPLOY" = "true" ]; then
  echo "→ 本次未构建、未启动容器。要部署请执行 ./deploy.sh（引导）或 ./deploy.sh $TRAIN_MODE"
  exit 0
fi

# ---------- 端口占用检查（只检查本次真正会映射到宿主机的端口）----------
# 本项目自己已在跑的容器所映射的端口不算冲突（重复部署要能沿用现有容器）
OWN_PORTS="
$(docker compose ps -q 2>/dev/null | while read -r cid; do
     [ -n "$cid" ] && docker port "$cid" 2>/dev/null
   done | sed -n 's/.*->.*:\([0-9][0-9]*\)$/\1/p' | sort -u)
"

check_port() {
  local p="$1" name="$2"
  case "$OWN_PORTS" in
    *"
$p
"*) echo "  · 端口 $p 由本项目已在跑的容器占用（$name），重复部署沿用现有容器"; return 0 ;;
  esac
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn 2>/dev/null | grep -q ":$p "; then
      echo "✗ 端口 $p 已被占用（$name），请改配置里对应的映射端口" >&2
      return 1
    fi
  elif command -v lsof >/dev/null 2>&1; then
    if lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "✗ 端口 $p 已被占用（$name），请改配置里对应的映射端口" >&2
      return 1
    fi
  fi
}

PORT_SPECS="$WEB_PORT:web|$JAVA_PORT:manager|$PY_PORT:train-api"
if is_true "$BUILTIN_MYSQL"; then PORT_SPECS="$PORT_SPECS|$MYSQL_EXPOSE_PORT:mysql"; fi
if is_true "$BUILTIN_REDIS"; then PORT_SPECS="$PORT_SPECS|$REDIS_EXPOSE_PORT:redis"; fi
if is_true "$BUILTIN_STORAGE"; then
  PORT_SPECS="$PORT_SPECS|$RUSTFS_API_PORT:rustfs|$RUSTFS_CONSOLE_PORT:rustfs-console"
fi
OLD_IFS="$IFS"; IFS='|'
for spec in $PORT_SPECS; do check_port "${spec%%:*}" "${spec#*:}" || exit 1; done
IFS="$OLD_IFS"

# ---------- 构建 ----------
# 显式列出服务，避免把另一种训练模式的镜像（CUDA 轮子约 2.5GB）也构建出来
BUILD_SERVICES="manager train-api web"
if [ "$TRAIN_MODE" = "gpu" ]; then
  BUILD_SERVICES="$BUILD_SERVICES train-worker-gpu"
else
  BUILD_SERVICES="$BUILD_SERVICES train-worker-cpu"
fi
echo "→ 构建镜像：$BUILD_SERVICES（首次约 3~10 分钟，取决于网络）"
# shellcheck disable=SC2086
docker compose build $BUILD_SERVICES

# ---------- 分阶段启动 ----------
# 内置组件与初始化任务先完成，再起应用，避免应用连不上依赖而反复重启
INFRA=""
if is_true "$BUILTIN_MYSQL"; then INFRA="$INFRA mysql"; fi
if is_true "$BUILTIN_REDIS"; then INFRA="$INFRA redis"; fi
if is_true "$BUILTIN_STORAGE"; then INFRA="$INFRA rustfs"; fi
if [ -n "$INFRA" ]; then
  echo "→ [1/3] 启动内置基础设施：$INFRA（等待健康检查通过）"
  # shellcheck disable=SC2086
  docker compose up -d --wait $INFRA || { echo "✗ 内置组件未就绪，用 docker compose logs 查看原因" >&2; exit 1; }
else
  echo "→ [1/3] 全部为外部组件，无需启动内置容器"
fi

echo "→ [2/3] 初始化任务"
if is_true "$BUILTIN_MYSQL" || is_true "$AUTO_DDL"; then
  docker compose run --rm db-init || { echo "✗ 建表失败：检查 $MYSQL_HOST:$MYSQL_PORT 的账号口令与建表权限" >&2; exit 1; }
else
  echo "  - 跳过建表（已有库 $MYSQL_DATABASE，表结构由你自己维护）"
fi
if is_true "$BUILTIN_STORAGE" || is_true "$AUTO_BUCKET"; then
  docker compose run --rm bucket-init || { echo "✗ 建桶失败：检查 $S3_ENDPOINT 与密钥长度限制" >&2; exit 1; }
else
  echo "  - 跳过建桶（桶由你维护，或由应用首次访问时自动创建）"
fi

echo "→ [3/3] 启动应用：manager / train-api / train-worker-$TRAIN_MODE / web"
docker compose up -d --remove-orphans

echo "→ 等待服务响应"
wait_url() {
  local url="$1" name="$2" svc="$3" i
  for i in $(seq 1 60); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      echo "  ✓ $name 就绪"
      return 0
    fi
    sleep 5
  done
  echo "  ✗ $name 在 300 秒内未就绪：$url"
  echo "    排查：docker compose ps ；docker compose logs --tail=100 $svc"
  return 1
}
wait_url "http://127.0.0.1:${PY_PORT}/api/health"    "训练/推理服务 (FastAPI)" train-api
wait_url "http://127.0.0.1:${JAVA_PORT}/v3/api-docs" "管理后端 (Spring Boot)" manager
wait_url "http://127.0.0.1:${WEB_PORT}/healthz"      "前端 (nginx)" web

docker compose ps

# ---------- 结果汇总 ----------
LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
if [ -z "$LAN_IP" ]; then LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)"; fi
cat <<EOF

=============================================================================
部署完成（训练模式：$TRAIN_MODE）

  前端入口      http://${LAN_IP}:${WEB_PORT}
  管理后端      http://${LAN_IP}:${JAVA_PORT}/swagger-ui.html
  训练服务      http://${LAN_IP}:${PY_PORT}/docs
  数据库        $(label_bool "$BUILTIN_MYSQL") → $MYSQL_HOST:$MYSQL_PORT / 库 $MYSQL_DATABASE / 账号 $MYSQL_USER
  缓存          $(label_bool "$BUILTIN_REDIS") → $REDIS_HOST:$REDIS_PORT（指标库 $REDIS_METRICS_DB）
  对象存储      $(label_bool "$BUILTIN_STORAGE") → $S3_ENDPOINT
$(is_true "$BUILTIN_STORAGE" && echo "  存储控制台      http://${LAN_IP}:${RUSTFS_CONSOLE_PORT}（账号 $S3_ACCESS_KEY）" || echo "  存储桶          $S3_BUCKET_DATASETS / $S3_BUCKET_MODELS / $S3_BUCKET_TESTS")

建议的下一步：
  1. 打开前端「基础模型」页 → 「同步官方清单」→ 下载需要的 .pt 权重
     （离线环境请把权重放到 model-data 卷的 base_models 目录，再点「刷新状态」）
  2. 新建数据集 → 导入 zip（YOLO 标注包 / COCO JSON）或上传图片
  3. 在线标注后务必点「导出标注」，再发起训练 —— 训练读的是存储里的 labels/*.txt
  4. 训练完成 → 模型版本「激活」→ 对外识别接口 POST /api/inference/detect

常用命令：
  ./deploy.sh config            重新引导配置（只改 .env，不动容器）
  ./deploy.sh status            查看容器状态
  ./deploy.sh logs              跟踪日志
  ./deploy.sh schema            只重跑建表脚本
  ./deploy.sh down              停止并删除容器（数据卷保留）
  切换到另一种训练模式：./deploy.sh gpu 或 ./deploy.sh cpu
=============================================================================
EOF
