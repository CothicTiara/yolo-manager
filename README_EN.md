# YOLO Vision Model Management Platform

[中文说明](README.md)

A one-stop system for training and managing object-detection models: image upload,
online annotation, dataset management, automated training, model versioning and
inference testing. The platform combines a Vue 3 front end, a Spring Boot management
backend and a Python FastAPI/Celery training service on top of S3 object storage,
closing the loop from raw data to a deployed model and lowering the barrier for
vision AI applications.

## Component documentation

| Component | Documentation | Stack | Default port | Responsibility |
|---|---|---|---|---|
| Front end | [vue-subject/README_EN.md](vue-subject/README_EN.md) | Vue 3 + Element Plus + ECharts + Vite 5 | 5173 | 7 pages: datasets, annotation, training, models, base models, guide (bilingual UI, Chinese by default) |
| Management backend | [java-manger-subject/README_EN.md](java-manger-subject/README_EN.md) | Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 + JDK 17 | 8080 (prod 12391) | Business and data management, task orchestration, model versions, public inference API |
| Training / inference service | [python-yolo-subject/README_EN.md](python-yolo-subject/README_EN.md) | FastAPI + Celery 5.4 + Ultralytics | 8000 (prod 12390) | Training execution, metric reporting, artefact archiving, inference |
| Live API docs | [section 4 of the backend doc](java-manger-subject/README_EN.md#4-api-overview) | SpringDoc / Swagger UI | `http://localhost:8080/swagger-ui.html` | All management REST endpoints; the training service also exposes `http://localhost:8000/docs` |

The user interface itself is bilingual (Chinese / English, Chinese by default) with a switcher in
the top bar; see [section 4 of the front-end doc](vue-subject/README_EN.md#4-internationalisation-chinese--english).
Backend messages and Swagger pages are not localised.

## Architecture at a glance

```
          browser
             |
             v
   +-------------------+        /api and /storage proxied
   |  vue-subject      | 5173
   |  Vue 3 + Element+ |
   +---------+---------+
             | HTTP (Result: code/message/data)
             v
   +-------------------+        +--------------------+
   | java-manger-      | 8080   | MySQL 8            | 9 business tables
   | subject           +------->| yolo_manager       | (dataset / annotation /
   | Spring Boot       |        +--------------------+  model_version / ...)
   +---+-----------+---+
       |           |        +--------------------+
       | REST      |<------>| Redis              | db5 metrics train:metrics:{id}
       | dispatch  |  HTTP  | broker db1/result  | db1 train:queue (redis mode)
       | callback  |  Redis | db2 / queues       |
       v           v        +--------------------+
   +-------------------+
   | python-yolo-      | 8000        +------------------+
   | subject (FastAPI) +------------>| RustFS / MinIO   | yolo-datasets
   +-------------------+   S3 SDK    | (S3-compatible)  | yolo-models
   | Celery worker     |             +------------------+ yolo-tests
   | yolo.train_task   |
   +-------------------+
```

Data flow: the browser only talks to the management backend. The backend owns the
business logic and the database, and forwards training/inference requests to the
Python service. The Python service reads and writes images and weights over S3,
pushes live metrics to Redis, writes final state to MySQL, and calls the backend
back once training finishes so the new model version gets registered.

## Core workflow

| Step | Action | Components |
|---|---|---|
| 1 Create dataset | Provide name (<=100 chars) / description / `trainRatio` (default 0.8) -> a `dataset` row | front end -> Java |
| 2 Import data | Upload images in bulk, or a zip (YOLO annotation package / COCO JSON / plain images); processed asynchronously with polled progress | front end -> Java -> S3 |
| 3 Annotate | Draw boxes on the canvas and attach labels; coordinates normalised against the original image (0~1, 6 decimals) | front end -> Java -> MySQL |
| 4 **Export annotations** | Turn the structured annotations into YOLO `labels/{imageId}.txt` files in storage | Java -> S3 |
| 5 Train | Pick a base model (BASE = new training / TRAINED = continue) plus epochs/batch/imgsz -> dispatch to Celery -> live curves -> callback registers the version | Java -> Python -> Celery |
| 6 Publish and call | Activate a version as the default model; test it inside the platform or have third parties call `POST /api/inference/detect` | front end -> Java -> Python |

> Important: **training reads the annotation files that were exported to storage**.
> After annotating online you must run step 4 "Export annotations" first, otherwise
> training fails because no labels are found. The same manual ships with the product
> on the front-end "Guide" page (`/guide`).

## Repository layout

```
yolo-manager/
├── docker-compose.yml       # deployment manifest: MySQL + Redis + RustFS + the three apps + worker
├── deploy.sh                # Linux / macOS deployment script, Chinese prompts (wizard + CONFIG BLOCK)
├── deploy_en.sh             # the same script with English prompts
├── deploy.ps1               # Windows (Docker Desktop) equivalent, Chinese prompts
├── deploy_en.ps1            # the same PowerShell script with English prompts
├── vue-subject/             # front end (Dockerfile + nginx.conf, see its README)
├── java-manger-subject/     # Spring Boot management backend (Dockerfile; DDL in src/main/resources/db/)
├── python-yolo-subject/     # FastAPI + Celery training and inference service (Dockerfile, see its README)
├── .gitignore               # ignore rules for all three components (build output, logs, .env)
├── LICENSE
├── README.md                # Chinese version of this file
└── README_EN.md             # this file
```

## External dependencies

| Dependency | Version | Purpose |
|---|---|---|
| MySQL | 8.0+ | business database `yolo_manager` (shared by Java and Python); the DDL lives in [section 6 of the backend doc](java-manger-subject/README_EN.md), file `src/main/resources/db/schema.sql` (existing databases can also run `db/patch-2026-11-26-model-series.sql` on its own) |
| Redis | 5+ recommended (3.2 works via RESP2) | live training metric cache, Celery broker/result, optional task queue |
| Object storage | RustFS / MinIO or any S3-compatible service | three buckets: `yolo-datasets`, `yolo-models`, `yolo-tests`; the Java side can switch to `local` disk mode to drop this dependency |
| Docker | Engine >= 20.10 + Compose v2 (or Docker Desktop) | used by the one-click deployment; GPU mode additionally needs nvidia-container-toolkit |
| JDK / Maven | 17 / >= 3.8 (no mvnw) | building the backend locally (the Docker build is multi-stage, nothing needed on the host) |
| Python | 3.10+ (torch/ultralytics separately for training) | training and inference service |
| Node.js | 18+ (Vite 5) | front-end development and build |
| GPU | optional | only training needs it; the management flow still runs without torch installed |

## One-click deployment (Docker, recommended)

A single command starts every dependency and application: MySQL 8.0, Redis 7,
RustFS (S3-compatible object storage), the management backend, the training/inference
service, the Celery worker and the nginx front end. All data lives in named volumes
(`mysql-data` / `redis-data` / `model-data` / `rustfs-data-0..3`), so `down` does not
lose anything.

**By default every infrastructure component comes from a docker image. If you already
run MySQL / Redis / object storage, answer "use the existing instance" in the wizard:
the matching container is then not started and does not occupy a host port.** The block
at the top of each script (`[ CONFIG BLOCK ]`) holds the default answers -- pressing
Enter accepts them. To deploy without any questions, edit that block and run
`env` / `cpu` / `gpu`. Both languages write the same `.env` next to the script
(gitignored); `docker-compose.yml` only reads it, so never put passwords in the compose
file by hand.

```bash
# Linux / macOS -- Chinese prompts
./deploy.sh                 # interactive wizard: training mode, each component self-hosted or external, ports, training parameters
./deploy.sh config          # wizard only, writes .env (no build, no start)
./deploy.sh env             # no questions: build .env from the CONFIG BLOCK and validate the manifest
./deploy.sh cpu             # no questions, everything from docker images, CPU training
./deploy.sh gpu             # no questions, everything from docker images, GPU training
./deploy.sh status|logs|down|restart|schema
vim deploy.sh               # edit the CONFIG BLOCK defaults (regenerate afterwards with ./deploy.sh config)

# Same script with English prompts
./deploy_en.sh cpu
./deploy_en.sh config
./deploy_en.sh status|logs|down|restart|schema

# Host already runs MySQL/Redis? Move the containers' published ports out of the way
# (in-container traffic still uses 3306/6379)
MYSQL_EXPOSE_PORT=13306 REDIS_EXPOSE_PORT=16379 ./deploy_en.sh env
```

```powershell
# Windows (Docker Desktop, WSL 2 backend recommended) -- Chinese prompts
powershell -ExecutionPolicy Bypass -File .\deploy.ps1              # interactive wizard
.\deploy.ps1 -Action config                                       # wizard, writes .env only
.\deploy.ps1 -Action env -Guide none                              # no questions, writes and validates .env
.\deploy.ps1 -Mode cpu                                            # no questions, everything from docker images
.\deploy.ps1 -Mode gpu -CudaTag cu121 -GpuCount 1                  # GPU with an explicit wheel tag and card count
.\deploy.ps1 -Action status                                       # status / logs / down / restart / schema

# English prompts: identical behaviour, same .env
.\deploy_en.ps1 -Action config
.\deploy_en.ps1 -Mode gpu -CudaTag cu121 -GpuCount 1
.\deploy_en.ps1 -Action status

# Host already runs MySQL / Redis: -MysqlExposePort / -RedisExposePort move the containers'
# published ports aside (RustfsApiPort / RustfsConsolePort do the same for storage)
.\deploy.ps1 -Mode cpu -MysqlExposePort 13306 -RedisExposePort 16379

# Attaching external components without questions (CI / repeated deployments):
# passing -External* means "use the existing service"
.\deploy_en.ps1 -Mode cpu -Action env -ExternalMysql 10.0.0.9 -MysqlDatabase glm `
             -MysqlUser glm -MysqlPassword 'pwd' -AutoDdl `
             -ExternalRedis 10.0.0.9 -ExternalStorage http://10.0.0.9:9000 -AutoBucket
```

### What the wizard asks

| Step | Default (everything from docker) | Extra answers when choosing "existing instance" |
|---|---|---|
| Training mode | `cpu`, starts `train-worker-cpu` | `gpu` starts `train-worker-gpu` (also asks for the CUDA tag and card count) |
| MySQL | self-hosted; database and account created by the image, `db/*.sql` runs automatically (patch + schema, both idempotent) | address, port, database name, application credentials; then "run the DDL as well?" (if yes, an account with DDL rights) |
| Redis | self-hosted, started with `--requirepass` | address, port, password (may be empty) |
| Object storage | self-hosted RustFS, three buckets created | endpoint, access/secret key, region, HTTPS?; then "create the missing buckets?" |
| Ports | front end 80, backend 8080, training 8000 | host port mappings of the self-hosted components: `WEB_PORT` / `JAVA_PORT` / `PY_PORT`, plus `MYSQL_EXPOSE_PORT` / `REDIS_EXPOSE_PORT` / `RUSTFS_API_PORT` / `RUSTFS_CONSOLE_PORT` (use them when a local MySQL, Redis or MinIO already owns the default port; in-container addresses do not change) |
| Training | install torch, DataLoader workers, batch_size, shared memory, PyPI index | - |
| Cross-host and callbacks | containers talk to each other, callback not verified | training service address, callback base URL, token, JVM options |

Two wizard depths: `1 = full` (also asks for Redis database indexes, bucket addressing
style, PyPI index, cross-host addresses) and `2 = quick` (only the training mode, the
three self-hosted/external choices, the essential passwords and ports, and the core
training parameters). Every answer is validated for emptiness, port range and endpoint
scheme; invalid input is re-asked instead of corrupting `.env`.

### Profiles and external components

`COMPOSE_PROFILES` in `.env` decides which services are actually enabled, so the
container list for "everything self-hosted" differs a lot from "everything external":

| profile | services enabled | when it is set |
|---|---|---|
| `cpu` / `gpu` | the matching `train-worker-*` | one of the two, always |
| `builtin-mysql` | `mysql` + `db-init` | you chose a self-hosted MySQL |
| `builtin-redis` | `redis` | you chose a self-hosted Redis |
| `builtin-storage` | `rustfs-init` + `rustfs` + `bucket-init` | you chose a self-hosted RustFS |
| `auto-ddl` | `db-init` | external MySQL, but the script should still create the tables |
| `auto-bucket` | `bucket-init` | external object storage, but buckets should be created |

Example: self-hosted Redis + external MySQL (tables already exist) + external MinIO
-> `COMPOSE_PROFILES=cpu,builtin-redis`. Changing the training mode or the component
source just means re-running the script; `up` uses `--remove-orphans`, so containers
that are no longer needed are removed while the named volume data is kept.

Four hard requirements when attaching external components (the wizard prints a
reminder for each one):

1. **The address must be reachable from the application containers**: `127.0.0.1` /
   `localhost` inside a container is the container itself -- use the host IP or the
   address the host has on the container network.
2. **MySQL authentication plugin**: an account using `caching_sha2_password` cannot be
   used by the Python side (PyMySQL without cryptography); switch it to
   `mysql_native_password`, or add cryptography to the image. The database charset must
   be `utf8mb4`.
3. **The Redis metrics index must match on both sides**: `REDIS_METRICS_DB` is injected
   into both Java's `spring.data.redis.database` and Python's `REDIS_METRICS_DB`, so
   using the wizard/CONFIG BLOCK keeps them aligned. An external Redis must allow and be
   able to write that database index.
4. **Object storage must support path-style addressing**: the Python side is hard-wired
   to `addressing_style=path` (MinIO/RustFS support it). A cloud service offering only
   virtual-host style needs a path-style-compatible endpoint. The endpoint must also
   carry a scheme (`http://` or `https://`).

### Why the script owns the start order

If a service that is switched off by a profile appears in another service's
`depends_on`, compose fails outright with `depends on undefined service` -- and with
external components, `mysql`, `redis` and `rustfs` may each be switched off. So
`docker-compose.yml` only declares `depends_on` between services that are always
enabled (`web -> manager`, `train-worker-* -> train-api`); the infrastructure and the
initialisation jobs are sequenced by the script in three stages:

1. `docker compose up -d --wait`: starts only the enabled self-hosted components and
   waits for their health checks;
2. `docker compose run --rm db-init` / `bucket-init`: create the tables and the buckets,
   then exit;
3. `docker compose up -d --remove-orphans`: starts manager / train-api /
   train-worker-* / web.

Plain `docker compose up -d` also works in the "everything self-hosted" case: `db-init`
polls until MySQL answers, so it cannot lose the race, while the other services may
cycle a few restarts on the first start until dependencies answer (every service uses
`restart: unless-stopped` and converges on its own). The script is still recommended.

Services and ports (all changeable in the CONFIG BLOCK or the wizard):

| Entry point | Default address | Notes |
|---|---|---|
| Front end | `http://<host IP>:80` | nginx container; `/api`, `/storage` and swagger are reverse-proxied to `manager:8080` |
| Management backend | `http://<host IP>:8080/swagger-ui.html` | Spring Boot |
| Training service | `http://<host IP>:8000/docs` | FastAPI |
| RustFS console | `http://<host IP>:9001` | log in with the access/secret key from the wizard; mapped only when self-hosted |
| MySQL / Redis | `<host IP>:3306` / `:6379` | mapped only when self-hosted; application containers use the service names `mysql` / `redis` |

What CPU and GPU mode actually change:

| Difference | cpu | gpu |
|---|---|---|
| Worker started | `train-worker-cpu` | `train-worker-gpu` (with the nvidia device reservation; `GPU_COUNT` controls how many cards) |
| Profile mechanism | `COMPOSE_PROFILES` goes into `.env`; re-run the script to switch (`up --remove-orphans` replaces the other worker) | |
| torch wheels | `TORCH_INDEX_URL_CPU` (whl/cpu) | `TORCH_INDEX_URL_GPU` (built from `CUDA_TAG`, default cu124) |
| `DEVICE` | cpu | cuda (passed to training only; `train-api` always uses the CPU wheels) |
| `TRAIN_WORKERS` / `DEFAULT_BATCH_SIZE` | 4 / 8 | 8 / 16 |
| Pre-flight check | docker only | also checks `nvidia-smi` and the docker nvidia runtime, and aborts if either is missing |

Other implementation details:

1. **DDL**: the `db-init` container first polls until MySQL is reachable
   (`mysqladmin ping`, up to 180s; a plain `docker compose up -d` has none of the
   script's `--wait` stages, so without waiting it would exit with a connection error),
   then executes `java-manger-subject/src/main/resources/db/*.sql` in alphabetical
   order (`patch-2026-11-26-model-series.sql` first, `schema.sql` afterwards; everything
   `IF NOT EXISTS` / guarded by `information_schema`, safe to re-run) and substitutes the
   hard-coded `yolo_manager` with the `MYSQL_DATABASE` from the CONFIG BLOCK. Connection
   details also come from the configuration, so it works against an external MySQL too
   (`auto-ddl`).
2. **Buckets**: `bucket-init` uses `minio/mc` to create the three buckets
   (`mc mb --ignore-existing`) and retries on its own until the object store answers,
   without relying on a health check. The endpoint comes from the configuration, so
   external storage only needs `auto-bucket`. In addition, the Python `storage.py`
   creates a bucket on `head_bucket` 404 as a fallback.
3. **RustFS permissions**: the official image runs as UID 10001 while a freshly created
   named volume belongs to root, hence the `rustfs-init` container does the `chown`
   first.
4. **MySQL authentication**: a self-hosted instance is started with
   `mysql_native_password` forced, otherwise PyMySQL (without cryptography) cannot
   complete `caching_sha2_password` authentication.
5. **Image delivery**: the browser always reads object storage through Java's
   `/api/storage/{bucket}/{key}` proxy, so it never needs to reach RustFS directly and
   the S3 endpoint can stay the internal container name `http://rustfs:9000`.
6. **Intranet / offline**: the CONFIG BLOCK lets you replace `MYSQL_IMAGE`,
   `REDIS_IMAGE`, `ALPINE_IMAGE`, `RUSTFS_IMAGE`, `MC_IMAGE` and `PIP_INDEX_URL`. Base
   weights can be pre-placed in the `base_models` directory of the `model-data` volume
   (avoiding a GitHub download during training).
7. **Training on another machine** (worker on the GPU box): in wizard step 7 point the
   backend at `http://<train-host-IP>:<PY_PORT>` and the callback base URL at
   `http://<manager-host-IP>:<JAVA_PORT>`, otherwise the post-training callback never
   arrives and no model version is registered.

## Quick start (local, without Docker)

```bash
# 0. Prepare MySQL (schema.sql creates database and tables), Redis and an object store
#    (or set yolo.storage.type to local)

# 1. Management backend
cd java-manger-subject
mvn clean package -DskipTests
java -jar target/yolo-manager.jar                 # http://localhost:8080, Swagger UI /swagger-ui.html

# 2. Training service (FastAPI + Celery, two processes)
cd ../python-yolo-subject
cp .env.example .env                              # adjust database / Redis / S3 / directories
pip install -r requirements.txt                   # on a GPU box also install ultralytics / torch
uvicorn app.main:app --host 0.0.0.0 --port 8000   # terminal A
celery -A app.worker:celery_app worker --loglevel=info --pool=solo   # terminal B (--pool=solo is required on Windows)

# 3. Front end
cd ../vue-subject
npm install
npm run dev                                       # http://localhost:5173 (auto-increments if busy)
```

Open `http://localhost:5173` and walk through
"dataset -> import -> annotate -> export -> train -> activate -> call" once to verify
the whole chain.

## Cross-component consistency checklist

The most common configuration bugs are mismatches between the two sides. Check each:

| Item | Management backend | Training service | Why it must match |
|---|---|---|---|
| Metrics database | `spring.data.redis.database` = 5 | `REDIS_METRICS_DB` = 5 | the front-end curves come from Java reading Redis `train:metrics:{taskId}` |
| Bucket names | `yolo.storage.s3.bucket-*` | `S3_BUCKET_*` | training reads images and `labels/` from the same buckets |
| Callback address | `yolo.callback.base-url` (env `YOLO_CALLBACK_BASE_URL`) | handed to Python per task as `callbackUrl` | across hosts, Python must reach Java or no model version is registered |
| Callback token | `yolo.callback.token` | `CALLBACK_TOKEN` | a mismatch makes callbacks fail (empty on the Java side = no check) |
| Storage mode | `yolo.storage.type` | `STORAGE_TYPE` | `s3` on one side and `local` on the other means uploads work but training finds nothing |
| API prefix | `/api/**` | - | the Vite dev proxy points at 8080; in Docker `vue-subject/nginx.conf` reverse-proxies, so changing the backend port needs no front-end code change |

## Current deployment state and known limitations

1. Containerisation is complete: `docker-compose.yml` plus `deploy.sh` / `deploy_en.sh`
   and `deploy.ps1` / `deploy_en.ps1` (interactive wizard; everything from docker images
   by default, but it can also point at your existing MySQL / Redis / object storage),
   and a `Dockerfile` per component (the front end also has `nginx.conf`). See the
   section above. Passwords always come from `.env`; do not write them back into the
   compose file.
2. The Java side ships an `application-prod.yml` profile (`--spring.profiles.active=prod`)
   that is only meant for manual, non-Docker deployment; the Docker deployment uses
   `application.yml` plus environment-variable overrides (Spring Boot relaxed binding).
3. `application.yml`, `.env.example` and `.env` contain real intranet addresses and
   database/object-store passwords. Before open-sourcing or handing the code over,
   replace them with placeholders and inject the connection details through environment
   variables (with Docker that simply means editing the CONFIG BLOCK -- environment
   variables still override the hard-coded yml values). Neither this README nor the
   component READMEs copy any plaintext password.
4. **Nothing in the platform authenticates or authorises anyone**: both Java and Python
   open CORS, and `/api/inference/*` can be called by anybody -- intranet use or a
   gateway/reverse proxy in front is required.
5. Large-file support: the Java side allows 5120MB `multipart` and a 900s Tomcat
   connection timeout; import/export run in a background thread pool with a per-dataset
   mutex, and the front end polls progress endpoints.
6. `GET /api/train/{id}/metrics` reads the Redis cache (TTL 7 days); an empty curve
   after expiry is expected: **per-epoch metrics are not persisted to MySQL**. Long-term
   retention relies on the `results.csv` archived to S3 during training (callback field
   `resultsCsvKey`); the final mAP@50 / mAP@50-95 are stored in `model_version`.
7. GPU mode attaches the card to the training worker only; online inference in
   `train-api` (model testing, `/api/inference/detect`) still runs on CPU. Single-image
   inference is not heavy for a CPU; if you really need GPU inference, copy the
   `deploy.resources` fragment into `train-api` as described in the comment above
   `train-worker-gpu` in `docker-compose.yml`.

## Licence

See [LICENSE](LICENSE).
