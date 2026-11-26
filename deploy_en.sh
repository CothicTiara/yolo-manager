#!/usr/bin/env bash
# =============================================================================
# YOLO Vision Model Management Platform -- one-click deployment
# (Linux / macOS, Docker Compose)  --  ENGLISH VERSION
#
#   ./deploy_en.sh                # interactive wizard (recommended on first run)
#   ./deploy_en.sh cpu            # no questions, every component from docker images, CPU training
#   ./deploy_en.sh gpu            # no questions, every component from docker images, GPU training
#   ./deploy_en.sh config         # run the wizard, write .env only, no build and no start
#   ./deploy_en.sh env            # build .env from the CONFIG BLOCK below and validate the manifest
#   ./deploy_en.sh status|logs|down|restart|schema
#
# The Chinese version of this script is deploy.sh (identical logic, only the
# prompt text differs). Both write the same .env consumed by docker-compose.yml.
#
# The script writes its configuration into .env next to it (gitignored);
# docker-compose.yml only reads it. Re-running is safe: image layers are cached
# and both the DDL and the bucket creation are idempotent
# (IF NOT EXISTS / --ignore-existing).
# By default every component runs from a docker image, but the wizard can point
# MySQL / Redis / object storage at instances you already run; the matching
# containers are then disabled automatically.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# =============================================================================
# [ CONFIG BLOCK ] Everything below is a default value; the interactive wizard
# lets you confirm or override each of them on top of these values.
# To deploy without any questions, edit this block and run:
#   ./deploy_en.sh cpu / gpu / env
# Note: passwords must not contain $, spaces or "space+#" -- compose breaks
# when parsing .env in those cases.
# =============================================================================

# ---- Database ----
BUILTIN_MYSQL="true"             # true = run MySQL from a docker image; false = use an existing instance
MYSQL_HOST="mysql"               # fixed to mysql when self-hosted (service name on the compose network)
MYSQL_PORT="3306"                # port the applications connect to
MYSQL_EXPOSE_PORT="${MYSQL_EXPOSE_PORT:-3306}"   # extra host port mapping when self-hosted (for client tools); set it before invoking to avoid a local MySQL
MYSQL_DATABASE="yolo_manager"    # schema.sql has this name hard-coded; the script substitutes it automatically
MYSQL_USER="yolo"                # application account (created by the MySQL image when self-hosted)
MYSQL_PASSWORD="YoloApp#2026"    # application account password
MYSQL_ADMIN_USER="root"          # admin account used to create tables (fixed to root when self-hosted)
MYSQL_ADMIN_PASSWORD="YoloRoot#2026"
AUTO_DDL="false"                 # run schema.sql against an existing MySQL too (always on when self-hosted)

# ---- Redis ----
BUILTIN_REDIS="true"
REDIS_HOST="redis"
REDIS_PORT="6379"
REDIS_EXPOSE_PORT="${REDIS_EXPOSE_PORT:-6379}"
REDIS_PASSWORD="YoloRedis#2026"  # must not be empty when self-hosted: the container starts with --requirepass
REDIS_METRICS_DB="5"             # metrics DB; the same variable feeds Java and Python, so they always match
REDIS_DB_BROKER="1"
REDIS_DB_RESULT="2"

# ---- Object storage (S3 compatible) ----
BUILTIN_STORAGE="true"
S3_ENDPOINT="http://rustfs:9000" # must be reachable from the application containers; browsers never access it
S3_ACCESS_KEY="yoloRustfsAdmin"  # 5-20 characters, alphanumeric recommended
S3_SECRET_KEY="YoloRustfs#2026"  # 8-40 characters, alphanumeric recommended
S3_REGION="us-east-1"
S3_USE_SSL="false"
S3_PATH_STYLE="true"             # the Python side always uses path-style, external storage must support it
S3_BUCKET_DATASETS="yolo-datasets"
S3_BUCKET_MODELS="yolo-models"
S3_BUCKET_TESTS="yolo-tests"
AUTO_BUCKET="false"              # create missing buckets on an existing object store too (always on when self-hosted)
RUSTFS_API_PORT="${RUSTFS_API_PORT:-9000}"       # the next five entries only apply to a self-hosted RustFS
RUSTFS_CONSOLE_PORT="${RUSTFS_CONSOLE_PORT:-9001}"
RUSTFS_IMAGE="rustfs/rustfs:latest"   # pin a version if needed, e.g. rustfs/rustfs:1.0.4
# On a single host the four volumes share one block device, which makes RustFS's disk check refuse to start;
# set this back to false for a real multi-disk deployment
RUSTFS_BYPASS_DISK_CHECK="${RUSTFS_BYPASS_DISK_CHECK:-true}"

# ---- Application ports ----
WEB_PORT="80"                    # front end (this is what the browser opens)
JAVA_PORT="8080"                 # management backend
PY_PORT="8000"                   # training / inference service

# ---- Training and inference parameters ----
TRAIN_MODE_DEFAULT="cpu"         # cpu | gpu
INSTALL_YOLO="true"              # false = no torch/ultralytics in the image (management flow only)
CUDA_TAG="cu124"                 # GPU wheel tag: cu118 / cu121 / cu124 / cu126
GPU_COUNT="all"                  # how many GPUs to expose: all or an integer (e.g. 1)
WORKER_CONCURRENCY="1"           # concurrent training jobs per container, >1 needs care
TRAIN_WORKERS_CPU="4"            # DataLoader workers in CPU mode
TRAIN_WORKERS_GPU="8"            # DataLoader workers in GPU mode
DEFAULT_BATCH_SIZE_CPU="8"
DEFAULT_BATCH_SIZE_GPU="16"
TRAIN_SHM_SIZE="8g"              # shared memory; too little breaks DataLoader with "broken pipe"
PIP_INDEX_URL="https://pypi.org/simple"   # use a local mirror behind a slow/blocked network
DEFAULT_EPOCHS="100"
DEFAULT_IMGSZ="640"
DATASET_PREP_WORKERS="16"

# ---- Multi-host deployment and callbacks (keep the defaults on a single host) ----
TRAIN_SERVICE_URL="http://train-api:8000"   # training on another machine: http://<train-host>:<PY_PORT>
CALLBACK_BASE_URL="http://manager:8080"     # multi-host: http://<manager-host>:<JAVA_PORT>
TRAIN_DELIVERY_MODE="http"                  # http = call /api/train/start; redis = push to train:queue
CALLBACK_TOKEN=""                           # empty = callback is not verified; must match on both sides

# ---- Images and JVM (replace for intranet / offline environments) ----
MYSQL_IMAGE="mysql:8.0"
REDIS_IMAGE="redis:7-alpine"
ALPINE_IMAGE="alpine:3.20"        # used to fix ownership of the RustFS volumes
MC_IMAGE="minio/mc:latest"        # used to create the buckets
JAVA_OPTS="-Xms512m -Xmx1024m"

# =============================================================================
# [ END OF CONFIG BLOCK ] Normally nothing below needs to be changed
# =============================================================================

ACTION="${1:-guide}"
GUIDE_LEVEL="full"     # full | quick | none
SKIP_DEPLOY="false"
TRAIN_MODE="$TRAIN_MODE_DEFAULT"
TRAIN_WORKERS=""
DEFAULT_BATCH_SIZE=""

usage() {
  echo "Usage: $0 [guide|cpu|gpu|config|env|status|logs|down|restart|schema]" >&2
  echo "       guide (default) = interactive wizard; cpu/gpu = no questions," >&2
  echo "                        every component from a docker image" >&2
}

case "$ACTION" in
  cpu|gpu)
    TRAIN_MODE="$ACTION"; GUIDE_LEVEL="none" ;;
  guide|config|env)
    [ "$ACTION" = "config" ] && SKIP_DEPLOY="true"
    [ "$ACTION" = "env" ] && GUIDE_LEVEL="none" && SKIP_DEPLOY="true" ;;
  status|logs|down|restart|schema)
    if [ ! -f .env ]; then
      echo "[ERROR] No .env yet -- run ./deploy_en.sh (wizard) or ./deploy_en.sh cpu first" >&2
      exit 1
    fi
    case "$ACTION" in
      status)  exec docker compose ps ;;
      logs)    exec docker compose logs -f --tail=200 ;;
      down)    exec docker compose down ;;
      restart) exec docker compose restart ;;
      schema)
        echo "-> Running schema.sql (idempotent); target DB is MYSQL_HOST/MYSQL_DATABASE in .env"
        echo "   Using an existing MySQL without auto-DDL? Enable that option via ./deploy_en.sh config"
        docker compose run --rm db-init
        exit 0 ;;
    esac ;;
  *) usage; exit 1 ;;
esac

# ---------- input helpers used by the wizard ----------
# ask <var> <question> <default>: Enter keeps the default; on a non-interactive
# stream (pipe / cron) the default is used without asking
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

# ask_required <var> <question>: empty answers are re-prompted; EOF aborts
ask_required() {
  local __var="$1" __msg="$2" __v=""
  while :; do
    ask __v "$__msg (required)" ""
    if [ "${ASK_EOF:-0}" = "1" ]; then
      echo "[ERROR] Input ended before the required value '$__msg' was given -- aborting" >&2
      exit 1
    fi
    if [ -n "$__v" ]; then break; fi
    echo "    ! This value cannot be empty"
  done
  printf -v "$__var" '%s' "$__v"
}

ask_port() {
  local __var="$1" __msg="$2" __def="$3" __v
  while :; do
    ask __v "$__msg" "$__def"
    case "$__v" in
      ''|*[!0-9]*) echo "    ! Please enter a number in 1~65535"; continue ;;
    esac
    if [ "$__v" -lt 1 ] || [ "$__v" -gt 65535 ]; then echo "    ! Port out of range"; continue; fi
    break
  done
  printf -v "$__var" '%s' "$__v"
}

# ask_bool <var> <question> <default true|false>: accepts y/yes/n/no, stores true/false
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
    *) echo "    ! Only y / n accepted, keeping the default ($( [ "$__def" = true ] && echo yes || echo no ))"
       printf -v "$__var" '%s' "$__def" ;;
  esac
  if [ -n "${DEPLOY_DEBUG:-}" ]; then echo "    [debug] $__var = ${!__var}" >&2; fi
}

is_true() { [ "$1" = "true" ]; }
label_bool() { if is_true "$1"; then echo "self-hosted (docker image)"; else echo "external (existing)"; fi; }

# ---------- interactive wizard ----------
if [ "$GUIDE_LEVEL" != "none" ]; then
cat <<'EOF'

=============================================================================
 YOLO platform deployment wizard   (Enter keeps the value in brackets; y/n)
 Requires: Docker Engine >= 20.10 + Compose v2; GPU training also needs
           nvidia-container-toolkit
 Every component defaults to a docker image -- answer n to point it at a
 service you already run.
=============================================================================
EOF

  if [ "$GUIDE_LEVEL" = "full" ]; then
    echo
    ask _lvl "  Wizard depth (1=full, includes external-component details; 2=quick, everything from docker images)" "1"
    case "$_lvl" in
      2) GUIDE_LEVEL="quick" ;;
      *) GUIDE_LEVEL="full" ;;
    esac
  fi

  # ---- 1. training mode ----
  echo
  echo "[1/7] Training acceleration"
  while :; do
    ask TRAIN_MODE "  Train with CPU or GPU acceleration (cpu/gpu)" "$TRAIN_MODE"
    case "$TRAIN_MODE" in cpu|gpu) break ;; *) echo "    ! Only cpu or gpu" ;; esac
  done
  echo "    Note: the GPU is attached to the training worker only; online inference stays on CPU"

  # ---- 2. MySQL ----
  echo
  echo "[2/7] MySQL (needs MySQL 8.0+ with utf8mb4)"
  ask_bool BUILTIN_MYSQL "  Run MySQL from a docker image? (n = use your existing instance)" "$BUILTIN_MYSQL"
  if is_true "$BUILTIN_MYSQL"; then
    ask MYSQL_DATABASE       "  Database name (created if missing)" "$MYSQL_DATABASE"
    ask MYSQL_USER           "  Application account (created by the image)" "$MYSQL_USER"
    ask MYSQL_PASSWORD       "  Application account password" "$MYSQL_PASSWORD"
    ask MYSQL_ADMIN_PASSWORD "  root password (used to create database and tables)" "$MYSQL_ADMIN_PASSWORD"
    ask_port MYSQL_EXPOSE_PORT "  Host port mapping (change it if another MySQL uses it)" "$MYSQL_EXPOSE_PORT"
  else
    ask_required MYSQL_HOST  "  Existing MySQL address (must be reachable from the containers, not 127.0.0.1)"
    ask_port     MYSQL_PORT  "  Port" "3306"
    ask MYSQL_DATABASE       "  Database name (must exist, or the account may create it)" "$MYSQL_DATABASE"
    ask MYSQL_USER           "  Application account" "$MYSQL_USER"
    ask MYSQL_PASSWORD       "  Application account password" "$MYSQL_PASSWORD"
    ask_bool   AUTO_DDL      "  Let the script run schema.sql? (n if the tables already exist)" "$AUTO_DDL"
    if is_true "$AUTO_DDL"; then
      ask MYSQL_ADMIN_USER     "  Account with DDL privileges" "$MYSQL_ADMIN_USER"
      ask MYSQL_ADMIN_PASSWORD "  Its password" "$MYSQL_ADMIN_PASSWORD"
    fi
    printf '    ! If that instance uses caching_sha2_password, the Python side (PyMySQL without\n'
    printf '      cryptography) may fail to connect: switch the account to mysql_native_password,\n'
    printf '      or add cryptography to the image\n'
  fi

  # ---- 3. Redis ----
  echo
  echo "[3/7] Redis (Celery queues + training metrics cache)"
  ask_bool BUILTIN_REDIS "  Run Redis from a docker image?" "$BUILTIN_REDIS"
  if is_true "$BUILTIN_REDIS"; then
    ask REDIS_PASSWORD   "  Password (required when self-hosted)" "$REDIS_PASSWORD"
    ask_port REDIS_EXPOSE_PORT "  Host port mapping" "$REDIS_EXPOSE_PORT"
  else
    ask_required REDIS_HOST "  Existing Redis address"
    ask_port     REDIS_PORT "  Port" "6379"
    ask REDIS_PASSWORD      "  Password (press Enter for none)" ""
  fi
  ask_port REDIS_METRICS_DB "  Database index holding training metrics (shared by Java and Python)" "$REDIS_METRICS_DB"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_port REDIS_DB_BROKER "  Celery broker database index" "$REDIS_DB_BROKER"
    ask_port REDIS_DB_RESULT "  Celery result database index" "$REDIS_DB_RESULT"
  fi

  # ---- 4. object storage ----
  echo
  echo "[4/7] Object storage (S3 compatible, three buckets)"
  ask_bool BUILTIN_STORAGE "  Run RustFS from a docker image? (n = use your existing MinIO/RustFS etc.)" "$BUILTIN_STORAGE"
  if is_true "$BUILTIN_STORAGE"; then
    ask S3_ACCESS_KEY "  access key (5-20 characters, alphanumeric recommended)" "$S3_ACCESS_KEY"
    ask S3_SECRET_KEY "  secret key (8-40 characters, alphanumeric recommended)" "$S3_SECRET_KEY"
    ask_port RUSTFS_API_PORT "    S3 API host port mapping (change it if MinIO owns 9000)" "$RUSTFS_API_PORT"
    ask_port RUSTFS_CONSOLE_PORT "  Console host port mapping" "$RUSTFS_CONSOLE_PORT"
    if [ "$GUIDE_LEVEL" = "full" ]; then
      ask RUSTFS_IMAGE "  RustFS image (pin a version such as rustfs/rustfs:1.0.4 if needed)" "$RUSTFS_IMAGE"
    fi
  else
    ask_required S3_ENDPOINT "  Endpoint of the existing service, with scheme (e.g. http://172.16.5.121:9000)"
    ask_required S3_ACCESS_KEY "  access key"
    ask_required S3_SECRET_KEY "  secret key"
    ask S3_REGION "  region" "$S3_REGION"
    ask_bool S3_USE_SSL "  Access it over HTTPS?" "$S3_USE_SSL"
    ask_bool AUTO_BUCKET "  Let the script create the missing buckets? (n if they exist)" "$AUTO_BUCKET"
    printf '    ! The Python side always uses path-style addressing (bucket in the path), which\n'
    printf '      MinIO/RustFS support; virtual-host-only providers need a path-style endpoint\n'
  fi
  ask S3_BUCKET_DATASETS "  Dataset bucket" "$S3_BUCKET_DATASETS"
  ask S3_BUCKET_MODELS   "  Model bucket" "$S3_BUCKET_MODELS"
  ask S3_BUCKET_TESTS    "  Test bucket" "$S3_BUCKET_TESTS"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_bool S3_PATH_STYLE "  Should the Java side use path-style addressing too?" "$S3_PATH_STYLE"
  fi

  # ---- 5. application ports ----
  echo
  echo "[5/7] Public application ports"
  ask_port WEB_PORT  "  Front-end port (browser opens http://<IP>:port)" "$WEB_PORT"
  ask_port JAVA_PORT "  Management backend port" "$JAVA_PORT"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask_port PY_PORT "  Training/inference service port" "$PY_PORT"
  fi

  # ---- 6. training parameters ----
  echo
  echo "[6/7] Training parameters"
  ask_bool INSTALL_YOLO "  Install torch/ultralytics into the image? (n = management flow only, much faster build)" "$INSTALL_YOLO"
  if [ "$TRAIN_MODE" = "gpu" ]; then
    ask CUDA_TAG "  CUDA wheel tag (cu118/cu121/cu124/cu126)" "$CUDA_TAG"
    ask GPU_COUNT "  How many GPUs (all or a number)" "$GPU_COUNT"
    ask TRAIN_WORKERS "  DataLoader workers" "$TRAIN_WORKERS_GPU"
    ask DEFAULT_BATCH_SIZE "  Default batch_size" "$DEFAULT_BATCH_SIZE_GPU"
  else
    ask TRAIN_WORKERS "  DataLoader workers" "$TRAIN_WORKERS_CPU"
    ask DEFAULT_BATCH_SIZE "  Default batch_size" "$DEFAULT_BATCH_SIZE_CPU"
  fi
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask WORKER_CONCURRENCY "  Concurrent training jobs per worker container" "$WORKER_CONCURRENCY"
    ask TRAIN_SHM_SIZE "  Shared memory size" "$TRAIN_SHM_SIZE"
    ask PIP_INDEX_URL "  PyPI index (use a mirror where pypi.org is slow)" "$PIP_INDEX_URL"
  fi

  # ---- 7. multi-host and callbacks ----
  echo
  echo "[7/7] Multi-host deployment and callbacks (Enter for the defaults on one host)"
  if [ "$GUIDE_LEVEL" = "full" ]; then
    ask TRAIN_SERVICE_URL "  Address the backend uses to reach the training service (http://<train-host>:port)" "$TRAIN_SERVICE_URL"
    ask CALLBACK_BASE_URL "  Address the training service uses to call the backend (http://<manager-host>:port)" "$CALLBACK_BASE_URL"
    ask CALLBACK_TOKEN    "  Callback token (empty = not verified; must match on both sides)" "$CALLBACK_TOKEN"
    ask JAVA_OPTS         "  JVM options" "$JAVA_OPTS"
  else
    printf '    Keeping the defaults: containers talk to each other (train-api:8000 / manager:8080),\n'
    printf '    callbacks are not verified\n'
  fi

  echo
  ask_bool SKIP_DEPLOY "  Only generate .env this time (no build, no containers started)?" "$SKIP_DEPLOY"
fi

# ---------- normalization: self-hosted components use fixed in-network addresses ----------
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

# ---------- COMPOSE_PROFILES: training mode + enabled self-hosted components + init jobs ----------
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

# ---------- mandatory values ----------
check_required() {
  if [ -z "$2" ]; then echo "[ERROR] $1 must not be empty" >&2; exit 1; fi
}
check_required "MYSQL_DATABASE" "$MYSQL_DATABASE"
check_required "MYSQL_USER" "$MYSQL_USER"
check_required "MYSQL_PASSWORD" "$MYSQL_PASSWORD"
if ! is_true "$BUILTIN_MYSQL"; then check_required "MYSQL_HOST" "$MYSQL_HOST"; fi
if ! is_true "$BUILTIN_REDIS"; then check_required "REDIS_HOST" "$REDIS_HOST"; fi
if is_true "$BUILTIN_REDIS" && [ -z "$REDIS_PASSWORD" ]; then
  echo "[ERROR] A self-hosted Redis needs a password (compose starts it with --requirepass)" >&2
  exit 1
fi
check_required "S3_ACCESS_KEY" "$S3_ACCESS_KEY"
check_required "S3_SECRET_KEY" "$S3_SECRET_KEY"
check_required "S3_BUCKET_DATASETS" "$S3_BUCKET_DATASETS"
check_required "S3_ENDPOINT" "$S3_ENDPOINT"
case "$S3_ENDPOINT" in
  http://*|https://*) : ;;
  *) echo "[ERROR] S3_ENDPOINT must start with http:// or https://, got: $S3_ENDPOINT" >&2; exit 1 ;;
esac

# ---------- precondition checks ----------
command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker not found -- install Docker Engine (>=20.10) first" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "[ERROR] Cannot reach the Docker daemon (not running, or this user is not in the docker group)" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] Docker Compose v2 is missing -- install docker-compose-plugin" >&2; exit 1; }

if [ "$TRAIN_MODE" = "gpu" ]; then
  command -v nvidia-smi >/dev/null 2>&1 || { echo "[ERROR] GPU mode needs the NVIDIA driver on the host (nvidia-smi)" >&2; exit 1; }
  nvidia-smi >/dev/null 2>&1 || { echo "[ERROR] nvidia-smi failed -- check the driver status" >&2; exit 1; }
  if ! docker info 2>/dev/null | grep -qi 'nvidia'; then
    echo "! Warning: no nvidia runtime found in docker info. Install nvidia-container-toolkit"
    echo "  and run 'sudo systemctl restart docker', otherwise the GPU worker will not start."
  fi
fi

# ---------- write .env (compose reads this file from the same directory) ----------
ENV_FILE=".env"
BACKUP_NOTE="(first run, nothing backed up)"
if [ -f "$ENV_FILE" ]; then
  cp "$ENV_FILE" "$ENV_FILE.bak"
  BACKUP_NOTE="(previous .env backed up to .env.bak)"
fi

cat > "$ENV_FILE" <<EOF
# Generated by deploy_en.sh at $(date '+%F %T'); gitignored, do not commit
# Training mode: $TRAIN_MODE
# MySQL: $(label_bool "$BUILTIN_MYSQL") ($MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE)
# Redis: $(label_bool "$BUILTIN_REDIS") ($REDIS_HOST:$REDIS_PORT, metrics DB $REDIS_METRICS_DB)
# Object storage: $(label_bool "$BUILTIN_STORAGE") ($S3_ENDPOINT)
# To reconfigure: ./deploy_en.sh config (wizard), or edit the CONFIG BLOCK in
# deploy_en.sh and run ./deploy_en.sh env
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

echo "-> Wrote $ENV_FILE $BACKUP_NOTE"
echo "   profiles: $PROFILES"
echo "   training mode $TRAIN_MODE: DataLoader workers $TRAIN_WORKERS, default batch_size $DEFAULT_BATCH_SIZE"

echo "-> Validating docker-compose.yml"
docker compose config --quiet || { echo "[ERROR] The manifest failed to parse -- fix the values reported above" >&2; exit 1; }
echo "   [OK] configuration is valid"

if [ "$SKIP_DEPLOY" = "true" ]; then
  echo "-> Nothing was built or started. Deploy later with ./deploy_en.sh (wizard) or ./deploy_en.sh $TRAIN_MODE"
  exit 0
fi

# ---------- host port availability (only the ports this run actually publishes) ----------
# Ports published by this project's own running containers are not a conflict:
# re-deploying should reuse the existing containers
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
"*) echo "  - Port $p belongs to this project's own running container ($name); reusing it"; return 0 ;;
  esac
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn 2>/dev/null | grep -q ":$p "; then
      echo "[ERROR] Port $p is already in use ($name) -- change that mapping in the configuration" >&2
      return 1
    fi
  elif command -v lsof >/dev/null 2>&1; then
    if lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "[ERROR] Port $p is already in use ($name) -- change that mapping in the configuration" >&2
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

# ---------- build ----------
# Services are listed explicitly so the other training mode (CUDA torch, ~2.5 GB)
# is never built by accident
BUILD_SERVICES="manager train-api web"
if [ "$TRAIN_MODE" = "gpu" ]; then
  BUILD_SERVICES="$BUILD_SERVICES train-worker-gpu"
else
  BUILD_SERVICES="$BUILD_SERVICES train-worker-cpu"
fi
echo "-> Building images: $BUILD_SERVICES (3~10 minutes on the first run, network dependent)"
# shellcheck disable=SC2086
docker compose build $BUILD_SERVICES

# ---------- staged startup ----------
# Self-hosted infrastructure and the init jobs finish before the applications
# start, so the apps never restart in a loop waiting for their dependencies
INFRA=""
if is_true "$BUILTIN_MYSQL"; then INFRA="$INFRA mysql"; fi
if is_true "$BUILTIN_REDIS"; then INFRA="$INFRA redis"; fi
if is_true "$BUILTIN_STORAGE"; then INFRA="$INFRA rustfs"; fi
if [ -n "$INFRA" ]; then
  echo "-> [1/3] Starting self-hosted infrastructure:$INFRA (waiting for health checks)"
  # shellcheck disable=SC2086
  docker compose up -d --wait $INFRA || { echo "[ERROR] Infrastructure is not healthy -- see docker compose logs" >&2; exit 1; }
else
  echo "-> [1/3] Everything is external, no infrastructure containers to start"
fi

echo "-> [2/3] Initialization jobs"
if is_true "$BUILTIN_MYSQL" || is_true "$AUTO_DDL"; then
  docker compose run --rm db-init || { echo "[ERROR] DDL failed: check the account/DDL rights on $MYSQL_HOST:$MYSQL_PORT" >&2; exit 1; }
else
  echo "   - skipped DDL (existing database $MYSQL_DATABASE, schema maintained by you)"
fi
if is_true "$BUILTIN_STORAGE" || is_true "$AUTO_BUCKET"; then
  docker compose run --rm bucket-init || { echo "[ERROR] Bucket creation failed: check $S3_ENDPOINT and the key length limits" >&2; exit 1; }
else
  echo "   - skipped bucket creation (buckets maintained by you, or created on first access)"
fi

echo "-> [3/3] Starting applications: manager / train-api / train-worker-$TRAIN_MODE / web"
docker compose up -d --remove-orphans

echo "-> Waiting for the services to answer"
wait_url() {
  local url="$1" name="$2" svc="$3" i
  for i in $(seq 1 60); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      echo "   [OK] $name is up"
      return 0
    fi
    sleep 5
  done
  echo "   [FAIL] $name did not answer within 300 seconds: $url"
  echo "          Troubleshoot: docker compose ps ; docker compose logs --tail=100 $svc"
  return 1
}
wait_url "http://127.0.0.1:${PY_PORT}/api/health"    "training/inference service (FastAPI)" train-api
wait_url "http://127.0.0.1:${JAVA_PORT}/v3/api-docs" "management backend (Spring Boot)" manager
wait_url "http://127.0.0.1:${WEB_PORT}/healthz"      "front end (nginx)" web

docker compose ps

# ---------- summary ----------
LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
if [ -z "$LAN_IP" ]; then LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)"; fi
cat <<EOF

=============================================================================
Deployment finished (training mode: $TRAIN_MODE)

  Front end         http://${LAN_IP}:${WEB_PORT}
  Admin API         http://${LAN_IP}:${JAVA_PORT}/swagger-ui.html
  Train service     http://${LAN_IP}:${PY_PORT}/docs
  Database          $(label_bool "$BUILTIN_MYSQL") -> $MYSQL_HOST:$MYSQL_PORT / db $MYSQL_DATABASE / user $MYSQL_USER
  Cache             $(label_bool "$BUILTIN_REDIS") -> $REDIS_HOST:$REDIS_PORT (metrics DB $REDIS_METRICS_DB)
  Object storage    $(label_bool "$BUILTIN_STORAGE") -> $S3_ENDPOINT
$(is_true "$BUILTIN_STORAGE" && echo "  Storage console     http://${LAN_IP}:${RUSTFS_CONSOLE_PORT} (user $S3_ACCESS_KEY)" || echo "  Buckets             $S3_BUCKET_DATASETS / $S3_BUCKET_MODELS / $S3_BUCKET_TESTS")

Suggested next steps:
  1. Open "Base models" in the front end -> "Sync official catalogue" -> download
     the .pt weights you need. Offline: put them into base_models inside the
     model-data volume and click "Refresh status" instead.
  2. Create a dataset -> import a zip (YOLO annotation package / COCO JSON) or
     upload images.
  3. After annotating online always click "Export annotations" before starting
     training -- training reads labels/*.txt from object storage.
  4. When training finishes -> "Activate" a model version -> call
     POST /api/inference/detect.

Frequently used commands:
  ./deploy_en.sh config            re-run the wizard (rewrites .env only)
  ./deploy_en.sh status            container status
  ./deploy_en.sh logs              follow logs
  ./deploy_en.sh schema            re-run the DDL only
  ./deploy_en.sh down              stop and remove containers (volumes kept)
  Switch training mode: ./deploy_en.sh gpu or ./deploy_en.sh cpu
  Chinese version of this script: ./deploy.sh
=============================================================================
EOF
