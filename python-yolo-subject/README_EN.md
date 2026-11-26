# python-yolo-subject

YOLO vision model management platform · **Training & inference service** (FastAPI + Celery + Ultralytics YOLO)

[简体中文](README.md) · Internal docs: [Project overview](../README_EN.md) · [Management backend](../java-manger-subject/README_EN.md) · [Frontend](../vue-subject/README_EN.md)

## 1. Responsibilities

- Accept training tasks dispatched by the management backend and run Ultralytics YOLO training asynchronously through Celery
- Before training: fetch images and labels from object storage concurrently and generate `data.yaml`; after training: archive
  `best.pt` / `last.pt` / `results.csv` / `args.yaml`
- Report per-epoch metrics: write the live Redis cache (feeds the frontend curves, TTL 7 days); MySQL only receives task status and
  progress — per-epoch metrics are **never stored in MySQL**, historical rebuilds rely on the `results.csv` archived to S3
- Call back to the management backend `/api/train/callback` on completion to register the model version
- Serve inference: both the in-platform “test” feature and the public inference API land here
- Maintain base pretrained weights: parse the official list, download in background, report local status

## 2. Module layout

```
app/
├── main.py                  # FastAPI entry (12 routes) + lifespan init for storage/inference + CORS
├── worker.py                # Celery app and yolo.train_task (de-duplication, execution lock, cancel detection)
├── config.py                # Pydantic Settings: reads .env, derives broker/result/mysql URLs
├── models/schemas.py        # request/response models (TrainStartRequest, TrainMetric, BaseWeightDownloadRequest…)
├── services/
│   ├── training.py          # training orchestration: data prep → YOLO training → metric parsing → archiving → callback
│   ├── inference.py         # inference: download weights and images, run detection, render server-side annotated image
│   ├── base_models.py       # official weight list (ultralytics/assets) + local directory scan / background download
│   └── storage.py           # build_storage(): S3 (RustFS/MinIO) and local-disk implementations
└── utils/
    ├── db.py                # MySQL task status/progress and model version write-back + Redis metric cache (incl. the atomic CREATED→QUEUED update)
    ├── yolo_utils.py        # data.yaml generation, results.csv parsing
    ├── yolov13_compat.py    # YOLOv13 custom-module compatibility layer (injects 12 classes + the adaptive AAttn; call it before loading external weights)
    └── logging_utils.py     # logging setup (logs/ directory)
```

## 3. Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | Health check returning `status` / `yolo_installed` / `time` |
| POST | `/api/train/start` | Accepts a training task and pushes it to Celery (sync `def`, so a blocking broker can't stall the event loop) |
| POST | `/api/train/{task_id}/cancel` | Writes the cancel flag `train:cancel:{task_id}` (TTL 1 day); the worker polls it and aborts |
| GET | `/api/train/{task_id}/status` | Training status |
| GET | `/api/train/{task_id}/logs` | Training log lines |
| GET | `/api/train/{task_id}/metrics` | Epoch metrics, preferring the live Redis cache |
| POST | `/api/model/register` | Register a model version (equivalent entry point to the backend callback path) |
| POST | `/api/inference/predict` | Run inference; returns boxes, latency, server-side annotated image (base64 + storage key), original image size |
| GET | `/api/inference/health` | Inference self-check (weight loading capability etc.) |
| GET | `/api/base-models/local` | Scans `BASE_MODELS_DIR`, reporting downloaded `.pt` / in-progress `.pt.part` / failed `.pt.failed` |
| GET | `/api/base-models/official` | Parses the latest ultralytics GitHub Release tag and returns downloadable detection weights |
| POST | `/api/base-models/download` | Starts a background download thread per entry and immediately returns the `accepted` list |

FastAPI's own docs: `http://localhost:8000/docs` (OpenAPI JSON at `/openapi.json`).

## 4. Requirements and dependencies

- Python 3.10+
- MySQL 8 (database `yolo_manager`, the same one the management backend uses)
- Redis (Celery broker / result backend / live metrics — three roles)
- For training and inference: `pip install "ultralytics>=8.3.0" torch torchvision` (a GPU host additionally needs the CUDA build)

`requirements.txt` pins: fastapi 0.115.5, uvicorn[standard] 0.32.1, python-multipart >=0.0.9 (required by `Form` / `UploadFile`;
without it the service fails at startup), celery 5.4.0, kombu ≥5.3.4,<5.4, redis ≥4.5,<6, boto3 1.35.55,
pydantic 2.10.2, pydantic-settings 2.6.1, PyMySQL 1.1.1, SQLAlchemy 2.0.36, opencv-python-headless 4.10.0.84, Pillow 11.0.0,
requests 2.32.3, httpx 0.28.1. **ultralytics / torch / torchvision are commented out by default** because they are huge on machines
without a GPU and slow down local syntax checks — uncomment them when you actually need training or inference.

> Without torch/ultralytics installed FastAPI still starts; `/api/health` reports `yolo_installed: false` and the training/inference
> endpoints return a clear message, which makes it easy to integration-test the management flows first.

## 5. Configuration

`app/config.py` uses Pydantic Settings and loads `.env` from the same directory at startup (`env_file=".env"`, `extra="ignore"`),
so **restart the process after editing `.env`**. The full variable list with defaults is in `.env.example`; the important ones:

| Variable | Default | Description |
|---|---|---|
| `HOST` / `PORT` | 0.0.0.0 / 8000 | Service binding |
| `MYSQL_HOST` … `MYSQL_DATABASE` | localhost:3306 / yolo_manager | Task status/progress and model version write-back (per-epoch metrics go to Redis only); the user and password are `quote_plus`-escaped inside `mysql_url()`, so `@`, `#` and similar characters need no manual escaping |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / empty | Celery broker and result backend; the password is percent-encoded by `_redis_auth()` in `config.py` (`quote(pw, safe='')`) before it is placed in the URL, so the default `YoloRedis#2026` and other passwords containing `#` work as they are — unencoded, urlparse treats `#` as the start of a fragment, the netloc degenerates into `:YoloRedis` and Celery fails at startup with “Port could not be cast to integer value” |
| `REDIS_DB_BROKER` / `REDIS_DB_RESULT` | 1 / 2 | Celery queue and result backend |
| `REDIS_METRICS_DB` | 5 | **Must equal the backend's `spring.data.redis.database`**, otherwise the frontend training curve stays empty |
| `S3_ENDPOINT` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_REGION` / `S3_USE_SSL` | localhost:9000 / … / us-east-1 / false | RustFS/MinIO |
| `S3_BUCKET_DATASETS` / `_MODELS` / `_TESTS` | yolo-datasets / yolo-models / yolo-tests | Must match the backend's `yolo.storage.s3.*` bucket names |
| `STORAGE_TYPE` | s3 | `s3` or `local` (drops the object-storage dependency during development) |
| `WORK_DIR` | ./work | Temporary download directory for training data |
| `YOLO_PROJECT_DIR` | ./yolo_projects (`/model/yolo_projects` recommended in production) | Root directory for Ultralytics training output |
| `BASE_MODELS_DIR` | ./base_models (`/model/base_models` recommended in production) | Pretrained weight directory; place `yolov8n/s/m/l/x.pt`, `yolov11n/s/m/l/x.pt` etc. **offline in advance** to avoid runtime GitHub fetches |
| `DEFAULT_EPOCHS` / `DEFAULT_BATCH_SIZE` / `DEFAULT_IMGSZ` | 100 / 16 / 640 | Fallback parameters when a task omits them |
| `DEVICE` | empty | Left empty, ultralytics picks automatically; can be forced to `cuda` / `cpu` |
| `TRAIN_WORKERS` | 8 | DataLoader subprocesses; 8 recommended on Linux, **0 recommended on Windows dev machines** (avoids spawn compatibility issues) |
| `CALLBACK_TOKEN` | yolo-callback-token | Token sent to the management backend; pairs with its `yolo.callback.token` (no validation when that one is empty) |
| `DATASET_PREP_WORKERS` | 16 | Concurrent download threads during data preparation |

## 6. Running locally

```bash
cp .env.example .env      # adjust database / Redis / S3 / directories for your environment

pip install -r requirements.txt
# For training, add ultralytics / torch; the wheel index decides CPU vs CUDA (never mix them):
#   CPU: pip install --index-url https://download.pytorch.org/whl/cpu torch torchvision
#   GPU: pip install --index-url https://download.pytorch.org/whl/cu124 torch torchvision
#   then: pip install "ultralytics>=8.3.0" (still from PyPI; the installed torch already satisfies the version
#         requirement, so it will not be replaced by the CUDA build)
# Do NOT run `pip install --index-url https://download.pytorch.org/whl/cpu ultralytics`:
# that index only publishes torch/torchvision, not ultralytics or its dependencies.

# Terminal A: FastAPI (restart manually after code changes; --reload is off)
uvicorn app.main:app --host 0.0.0.0 --port 8000

# Terminal B: Celery worker —— the actual training executor
# Linux / macOS:
celery -A app.worker:celery_app worker --loglevel=info --concurrency=1
# Windows (important): the prefork pool crashes (ValueError: not enough values to unpack), use --pool=solo
celery -A app.worker:celery_app worker --loglevel=info --pool=solo
```

Without a Celery worker, `/api/train/start` only leaves the task in the queue and its status stays `queued` forever — when training
does not move, check that the worker is alive first.

### Docker deployment (CPU / GPU)

This module's [Dockerfile](Dockerfile) is used by the root `docker-compose.yml`, which defines three services:

| Service | Role | Key settings |
|---|---|---|
| `train-api` | FastAPI (long-running) | Always uses the CPU wheel image `yolo-manager/train:cpu`; `/model` mounts the `model-data` volume |
| `train-worker-cpu` | Celery worker (profile `cpu`) | `DEVICE=cpu`, `TRAIN_WORKERS` defaults to 4 |
| `train-worker-gpu` | Celery worker (profile `gpu`) | `DEVICE=cuda`, `TRAIN_WORKERS` defaults to 8, nvidia device reservation (`GPU_COUNT` controls how many) |

Notes:

- All of the configuration above (MySQL/Redis/S3/directories/default parameters) is injected by compose from `.env` via environment
  variables; `.env` is generated by the scripts, so don't hard-code credentials in compose. There are two ways to change it: run
  `./deploy.sh` / `.\deploy.ps1` for the interactive guide (choose “built-in” vs “use an existing instance” per component), or edit the
  [configuration] block at the top of the script and run `./deploy.sh env` / `.\deploy.ps1 -Action env -Guide none` (everything via Docker
  images, no questions). When you choose “use an existing instance”, the matching `builtin-*` profile is absent from `COMPOSE_PROFILES`,
  so that container neither starts nor occupies a host port — see
  [Profiles and external components](../README_EN.md#profiles-and-external-components) in the root README. If the host already runs its own
  MySQL/Redis, pass `-MysqlExposePort 13306 -RedisExposePort 16379` (or the same-named environment variables with the sh scripts) to move the
  containers' published ports out of the way; in-container connectivity is unaffected.
- Whether the image contains CPU or CUDA torch is decided by the build args `TORCH_INDEX_URL_CPU` / `TORCH_INDEX_URL_GPU` (the latter is
  assembled from `CUDA_TAG`); `INSTALL_YOLO=false` skips torch/ultralytics entirely, leaving only the management flows usable.
- `train-api` and the worker **must share the `model-data` volume** (WORK_DIR / YOLO_PROJECT_DIR / BASE_MODELS_DIR all live under
  `/model`), otherwise the worker cannot see the data the API prepared.
- Containers are Linux, so Celery's default prefork pool works fine — **`--pool=solo` is not needed** (that is a Windows dev-machine workaround).
- `TRAIN_SHM_SIZE` (default 8g) maps to `shm_size`; if DataLoader subprocesses report “pipe is already closed / bus error”, raise it first.

## 7. Training task execution chain

1. `POST /api/train/start` → `train_task.delay(task_id, dataset_id, model_base, epochs, batch_size, imgsz, callback_url, callback_token, base_model_type, base_version_id, expected_version, storage_prefix)`. The task name is fixed to `yolo.train_task` (`max_retries=0`); after recording the `async_result` locally the status is set to `QUEUED` through `db.mark_queued_if_created`, an atomic conditional update that only writes when the row is still `CREATED`, so it cannot overwrite the `RUNNING` status a worker has already claimed with “queued”
2. Two-layer de-duplication in the worker: first check the task status in MySQL, then acquire the Redis execution lock `train:running:{task_id}` (SET NX, renewed by a background thread while training runs). Duplicate dispatches are skipped outright so concurrent runs cannot delete each other's data directories
3. Download dataset images and `labels/` from S3 concurrently → generate `data.yaml`
4. Run Ultralytics training (the YOLOv13 compatibility layer is injected before weights are loaded) with output under `YOLO_PROJECT_DIR`; each epoch, parse `results.csv` and:
   - write `train:metrics:{task_id}` in Redis (metrics db, TTL 7 days)
   - update task status and progress in MySQL (`update_task_status`); per-epoch metrics are not written to MySQL
5. Poll `train:cancel:{task_id}` during training and abort when it appears
6. Archive `best.pt` / `last.pt` / `results.csv` / `args.yaml` into the `yolo-models` bucket: the directory prefers the `storage_prefix` sent by the management backend (`task-{taskId}`) and falls back to the version number when absent; version numbers may repeat across series, so the unique prefix is what prevents artefacts from overwriting each other
7. Call back `POST /api/train/callback` on the management backend to register the model version

## 8. Caveats and current state

1. **Redis protocol compatibility**: every Redis client explicitly sets `protocol=2` (RESP2) to stay compatible with old servers (e.g.
   Redis 3.2 on Windows, which has no `HELLO` command).
2. **Blocking endpoints must use sync `def`**: if `train_start` and friends were `async def`, the blocking `delay()`/broker calls would
   freeze the event loop and make the whole service — including `/api/health` — unresponsive.
3. **Celery visibility_timeout**: the broker default is one hour; with `acks_late`, very long trainings may be redelivered. The trade-offs
   are documented in `worker.py` comments — read them before changing concurrency or durations.
4. **`TRAIN_WORKERS > 0` temporarily lifts the daemon restriction inside prefork subprocesses** (see `training.py`); keep it at 0 on Windows.
5. **YOLOv13 self-trained weights**: `utils/yolov13_compat.py` embeds the 12 custom modules of the YOLOv13 fork (iMoonLab/yolov13) — DSC3k2 / DSC3k / DSBottleneck / DSConv / HyperACE / C3AH / AdaHGConv / AdaHGComputation / AdaHyperedgeGen / FuseModule / DownsampleConv / FullPAD_Tunnel — and injects them into the `ultralytics.nn.modules.block` / `conv` namespaces before `YOLO(model_path)` is called; otherwise unpickling such a weight fails with `has no attribute 'DSC3k2'`. `AAttn`, which exists in both but with a different submodule layout, is replaced by an adaptive subclass (so either weight loads). `ensure_yolov13_compat()` is idempotent and never touches site-packages, so upgrading ultralytics stays safe. Call sites: `services/inference.py` and the training executor in `services/training.py`.
6. **Version and storage path come from the backend**: `expected_version` / `storage_prefix` are supplied by the management backend (a version de-duplicated inside its series plus a unique storage prefix); the Python side no longer derives the directory itself and only falls back when the values are missing, so older callers keep working.
7. The official weight list only includes detection weights: `DETECT_RE = ^yolo(v?)(\d+)([nsmlx])\.pt$` excludes multi-task variants such as
   `-seg` / `-pose` / `-cls`, and names are normalised to the platform's `v`-prefixed form (`yolov11n`). GitHub downloads are prefixed with the
   `https://gh-proxy.com/` mirror; in a fully offline environment, place weights in `BASE_MODELS_DIR` manually instead.
8. CORS is wide open (`allow_origins=["*"]`), so the service belongs on an intranet or behind a gateway.
9. Containerised deployment is ready: this module has a [Dockerfile](Dockerfile); orchestration and CPU/GPU switching are covered in the
   previous section and in [One-command deployment (Docker)](../README_EN.md#one-command-deployment-docker-recommended).
10. Logs are written to `logs/` (configured by `logging_utils.py`) and temporary data to `work/`; both are ignored by the root `.gitignore`.
