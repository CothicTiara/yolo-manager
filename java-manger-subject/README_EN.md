# java-manger-subject

YOLO vision model management platform · **Management backend** (Spring Boot 3.3.5 / Java 17)

[简体中文](README.md) · Internal docs: [Project overview](../README_EN.md) · [Training & inference service](../python-yolo-subject/README_EN.md) · [Frontend](../vue-subject/README_EN.md)

## 1. Responsibilities

- Managing and persisting datasets, images, labels and online annotations
- Training task orchestration: validate dataset → persist → dispatch to the training service (HTTP or Redis queue)
- Receiving training-service callbacks and registering model versions (incremented inside a series, UUID auto-generated)
- Model version management: upload/register, rename, activate, download, delete, inference tests and test records
- Base pretrained weight catalogue management (official list sync, status write-back, enable/disable, download trigger)
- Public inference endpoint (`/api/inference/*`) for direct calls from third-party systems
- Async export of online annotations to YOLO `.txt`; async import of COCO/YOLO/image archives
- Object storage read/write (RustFS/MinIO over the S3 protocol; `local` mode falls back to local disk)

> There is currently **no login or authentication**: `entity/SysUser.java` and `mapper/SysUserMapper.java` exist but are not used
> by any controller or interceptor; every endpoint is anonymously accessible.

## 2. Tech stack

| Component | Version |
|---|---|
| Spring Boot | 3.3.5 |
| JDK | 17 (`maven.compiler.source/target`) |
| MyBatis-Plus | 3.5.9 (`mybatis-plus-spring-boot3-starter`) |
| AWS SDK v2 S3 | 2.29.24 |
| Hutool | 5.8.32 |
| SpringDoc OpenAPI | 2.6.0 (Swagger UI) |
| MySQL Connector/J / Redis / Lombok | Managed by the parent POM |

## 3. Layout

```
src/main/java/com/zyl/yolo/
├── YoloManagerApplication.java   # entry point (mainClass matches the exec/spring-boot plugins in pom.xml)
├── common/                       # Result, ResultCode, BusinessException, GlobalExceptionHandler, RedisUtils
├── config/                       # OpenApiConfig, RedisConfig, RestTemplateConfig, S3Config, WebMvcConfig
├── controller/                   # 9 REST controllers (see section 4)
├── service/                      # 11 services: Dataset/DatasetImage/DatasetImport/Export/Annotation/
│                                 #   Label/Train/ModelVersion/BaseModel/Inference/DatasetJobRegistry
├── mapper/                       # MyBatis-Plus BaseMappers (9)
├── entity/                       # database entities (9, all annotated with @TableName)
├── dto/                          # request/response objects (23)
└── storage/                      # StorageService abstraction + LocalStorageService / S3StorageService / BucketUtils

src/main/resources/
├── application.yml               # main configuration
├── application-prod.yml          # production profile (differences only)
└── db/
    ├── schema.sql                          # schema + in-place migration for existing databases (idempotent, auto-executed by the db-init container)
    └── patch-2026-11-26-model-series.sql   # 2026-11-26 model-series patch (manual upgrade, equivalent to the migration block in schema.sql)
```

> The schema of record is `src/main/resources/db/schema.sql` (see section 6). No Flyway/Liquibase, and no automatic
> DDL at startup: apply it yourself for a manual deployment, or let the `db-init` container do it in the Docker deployment
> (it runs `db/*.sql` in alphabetical order; both scripts check what already exists, so re-running them is harmless).
> Field semantics follow `@TableName` and the properties in `entity/*.java`.

## 4. API overview

Live docs: after startup open `http://localhost:8080/swagger-ui.html` (OpenAPI title “YOLO 视觉识别模型管理平台 API”, version 1.0.0 —
the title itself is Chinese-only by design and is not localised).

| Group | Method + path | Description |
|---|---|---|
| Datasets | `POST /api/dataset` | Create a dataset |
| | `GET /api/dataset` · `GET /api/dataset/{id}` | List / detail |
| | `DELETE /api/dataset/{id}` | Delete a dataset |
| | `POST /api/dataset/{id}/import` | Async archive import; `format` = AUTO (default, auto-detect) / COCO / YOLO / IMAGES, `jsonPath` points at the COCO JSON inside the archive |
| | `GET /api/dataset/{id}/import-status` | Import progress (NONE/PENDING/PROCESSING/DONE/FAILED) |
| Images | `POST /api/dataset/{datasetId}/upload` | Batch image upload |
| | `GET /api/dataset/{datasetId}/images` | Image list |
| | `DELETE /api/dataset/{datasetId}/images/{imageId}` | Delete an image |
| | `POST /api/dataset/{datasetId}/export` | Start async YOLO annotation export |
| | `GET /api/dataset/{datasetId}/export-status` | Export progress polling |
| Labels | `GET/POST /api/dataset/{datasetId}/label` | List / create labels |
| | `PUT /api/dataset/{datasetId}/label/{labelId}` | Rename (applies to existing annotations and exports) |
| | `DELETE /api/dataset/{datasetId}/label/{labelId}` | Delete a label |
| Annotations | `POST /api/annotation` | Save/update a single annotation |
| | `POST /api/annotation/sync/{imageId}` | Bulk sync all annotations of one image |
| | `GET /api/annotation/{imageId}` | Read an image's annotations |
| | `DELETE /api/annotation/{annotationId}` | Delete an annotation |
| Training | `POST /api/train` | Create and dispatch a training task |
| | `GET /api/train` · `GET /api/train/{id}` | Task list / detail |
| | `GET /api/train/base-models` | Base models for the training form (BASE + TRAINED) |
| | `GET /api/train/{id}/metrics` | Epoch metrics (prefers the live Redis cache) |
| | `POST /api/train/{id}/cancel` · `DELETE /api/train/{id}` | Cancel / delete (running tasks must be cancelled first) |
| | `POST /api/train/callback` | Called by the training service to register a model version |
| Models | `GET /api/model` | Version list (uuid, version, model name, series root id, base model, dataset, active flag) |
| | `POST /api/model/upload` | Upload a `.pt` directly to register a new version (a file name matching an existing model joins that series) |
| | `PUT /api/model/{id}/name` | Edit model name (≤100 chars) |
| | `POST /api/model/{id}/activate` | Activate (deactivates the other versions) |
| | `POST /api/model/{id}/test` | Upload a test image, returns results plus a server-side annotated image |
| | `GET /api/model/{id}/tests` | Historical test records |
| | `GET /api/model/{id}/download` | Attachment download of `best.pt` (file name `{version}.pt`) |
| | `DELETE /api/model/{id}` | Delete a version (S3 weights not shared by anyone else are cleaned up) |
| Base models | `GET /api/base-model/list` | Platform base model list |
| | `POST /api/base-model/refresh-status` · `/sync-official` | Refresh local status / sync the official weight list |
| | `POST /api/base-model/download` · `POST /api/base-model/{id}/enabled` · `DELETE /api/base-model/{id}` | Trigger download / enable-disable / delete |
| Public inference | `POST /api/inference/detect` | Object detection on an uploaded image; `modelUuid` is optional and defaults to the active version |
| | `GET /api/inference/models` | Available models (uuid, version, isActive, mAP) |
| Storage | `GET /api/storage/{bucket}/**` | In S3 mode, proxies object storage content so the frontend can load images |
| Static | `GET /storage/**` | Only with `storage.type=local`, mapped to local disk by `WebMvcConfig` |

Create-dataset body (`DatasetCreateRequest`): `name` (required, ≤100), `description` (≤500), `trainRatio` (train split ratio, default 0.8).

Create-training-task body (`TrainTaskCreateRequest`):

| Field | Constraint | Description |
|---|---|---|
| `datasetId` | required | Dataset id |
| `modelBase` | required when `baseModelType=BASE` | Base model name, e.g. `yolov8n` |
| `baseModelType` | `BASE` / `TRAINED`, default `BASE` | Fresh training / continue training |
| `baseVersionId` | required for `TRAINED` | Source `model_version.id` |
| `versionBump` | `MINOR` (+0.1, default) / `MAJOR` (+1.0) | Version step when continuing training (incremented inside the series) |
| `epochs` | 1 ~ 1000 | Epochs |
| `batchSize` | 1 ~ 256 | Batch size |
| `imgsz` | 32 ~ 4096 | Input size |

## 5. Unified response envelope

Except for downloads, Swagger and the storage proxy, every endpoint returns `Result<T>`:

```json
{ "code": 200, "message": "success", "data": {} }
```

When `code` is not 200, `GlobalExceptionHandler` has converted a `BusinessException(ResultCode)` into this envelope; the frontend
`request.js` interceptor surfaces the error and unwraps `data` accordingly.

## 6. Data model

Schema script: [`src/main/resources/db/schema.sql`](src/main/resources/db/schema.sql) (MySQL 8.0+, `utf8mb4_0900_ai_ci`,
every `CREATE TABLE` is `IF NOT EXISTS`, safe to re-run). The end of the file also carries an "in-place upgrade for existing
databases" migration block (it queries `information_schema` first and runs the DDL through `PREPARE/EXECUTE`, so no
`DELIMITER` is needed) which applies the 2026-11-26 model-series changes; databases upgraded by hand can instead run the
equivalent [`patch-2026-11-26-model-series.sql`](src/main/resources/db/patch-2026-11-26-model-series.sql) on its own (also idempotent).
The script was exported from a live database, checked
against `entity/*.java` one by one, and the Chinese comments that an earlier export had turned into `?` were rewritten;
the root `docker-compose.yml` executes `db/*.sql` automatically through the `db-init` container (it works for both the built-in
MySQL and an external one — the latter needs the `auto-ddl` profile, see
[the root README](../README_EN.md#one-command-deployment-docker-recommended)).

| Table | Entity | Purpose |
|---|---|---|
| `dataset` | Dataset | Datasets |
| `dataset_image` | DatasetImage | Images (incl. `split` information) |
| `label` | Label | Dataset labels |
| `annotation` | Annotation | Boxes (normalised centre point and size) |
| `train_task` | TrainTask | Training tasks and status |
| `model_version` | ModelVersion | Model versions, series root id, weight key, active flag |
| `base_model` | BaseModel | Base / pretrained weight catalogue |
| `inference_test` | InferenceTest | Inference test records |
| `sys_user` | SysUser | Reserved, not wired to any authentication |

### 6.1 Model version series (`model_version.series_id`)

A version number is **unique inside its series and may repeat across series**; `series_id` holds the id of the series root model:

| Origin | Series | Version rule |
|---|---|---|
| Initial training from a base model (`BASE`) | New series (`series_id` = own id) | Fixed `v1.0.0` |
| Continued training (`TRAINED`) | The series of the source model | Source version bumped by MINOR/MAJOR, de-duplicated inside the series |
| Upload whose file name matches a model | The series of that model | Highest version in the series + PATCH, collisions increment |
| Upload with a new name | New series | `v1.0.0` |

Related rules: the `expected_version` of live tasks (CREATED/QUEUED/RUNNING) counts as occupied inside the series, so
concurrent runs and callbacks cannot collide; version comparison is numeric segment-by-segment (string comparison made
`v1.9.0` greater than `v1.10.0`); rows with an empty `series_id` are treated as their own series. The Model Versions page
folds rows into a tree by `seriesId` (the root is the parent row), and the *Base model* column shows the **architecture name**
of the chain root (e.g. `yolo11n`), or "Uploaded manually" when the root is an upload.
Weight keys became unique directories so that equal version numbers in different series cannot overwrite each other:
training artefacts use `task-{taskId}/` (the Java side sends `storagePrefix`; an older Python service falls back to the version),
manual uploads use `uploads/{uuid}/`, and historical `{version}/` keys are still readable as they are.

MyBatis-Plus global config: snake_case → camelCase, `id-type=auto`, logical delete field `deleted` (1 deleted / 0 normal).

## 7. Configuration

Configuration is split in two layers; `application-prod.yml` lists only the differences and inherits the rest from `application.yml`:

| Setting | Default | Description |
|---|---|---|
| `server.port` | 8080 (prod: 12391) | Management backend port |
| `spring.datasource.url` | Written directly in the yml | MySQL 8, database `yolo_manager` |
| `spring.data.redis.database` | 5 | **Must match the training service's `REDIS_METRICS_DB`**, otherwise the frontend shows no live metrics |
| `spring.servlet.multipart.max-file-size/max-request-size` | 5120MB | Supports thousands of images / multi-GB archives |
| `yolo.storage.type` | s3 | `s3` (RustFS/MinIO) or `local` (local disk, for development) |
| `yolo.storage.s3.bucket-datasets/-models/-tests` | yolo-datasets / yolo-models / yolo-tests | One bucket per object type |
| `yolo.train-service.base-url` | http://localhost:8000 | FastAPI address (prod points at the training machine) |
| `yolo.train-service.mode` | http | `http` calls `/api/train/start`; `redis` pushes to `train:queue` |
| `yolo.callback.token` | empty | Callback token; no validation when empty |
| `yolo.callback.base-url` | `${YOLO_CALLBACK_BASE_URL:http://localhost:8080}` | Externally reachable address given to the training service; must be overridden for cross-host deployments |

**Only `STORAGE_LOCAL_ROOT`, `STORAGE_PUBLIC_BASE_URL` and `YOLO_CALLBACK_BASE_URL` are written as `${VAR:default}` placeholders.**
Database, Redis, S3 endpoint and credentials are currently hard-coded plaintext in the yml; the intranet addresses and passwords in
`application.yml` do not apply to other environments. There are two ways to override them:

- **One-command Docker deployment (recommended)**: no yml edits needed. Spring Boot's relaxed binding lets an environment variable of
  the same name override any property, which is exactly how `SPRING_DATASOURCE_URL` / `SPRING_DATA_REDIS_HOST` /
  `YOLO_STORAGE_S3_ENDPOINT` / `YOLO_TRAIN_SERVICE_BASE_URL` in `docker-compose.yml` take effect. Their values come from the `.env`
  generated by the scripts: edit the [configuration] block of `deploy.sh` / `deploy.ps1` (non-interactive) or confirm each item in the
  interactive guide. Choosing “use an existing instance” in the guide disables the matching container and points these addresses at
  your own service.
- **Manual deployment**: rewrite the yml values as `${MYSQL_HOST:localhost}`-style placeholders, or override at startup with
  `--spring.datasource.url=` / `--yolo.storage.s3.endpoint=`.

> The `.env.example` in this directory is only a variable list. Spring Boot does **not** read `.env` files automatically, so don't
> expect it to take effect on its own.

## 8. Interaction with the training service

| Direction | Target endpoint | Description |
|---|---|---|
| Java → Python | `POST /api/train/start` | Dispatch a training task (`mode=http`; payload carries `callbackUrl` / `callbackToken` / `expectedVersion`) |
| Java → Python | `POST /api/train/{taskId}/cancel` | Cancel: FastAPI writes `train:cancel:{taskId}` and revokes the Celery task (failures are only logged and never block the local CANCELLED state) |
| Java → Python | `POST /api/inference/predict` | Model testing and public inference |
| Java → Python | `GET /api/base-models/local` / `/official`, `POST /api/base-models/download` | Weight lists, status and downloads |
| Python → Java | `POST /api/train/callback` | Terminal training callback: writes back status/progress/map50/map5095 and the weight key, registers the model version |

Training status and logs are polled by the frontend directly from Java (`GET /api/train/{id}`, `/metrics`); **Java never pulls
status/logs from Python**. The Python endpoints `/api/train/{task_id}/status` and `/logs` exist mainly for operations and joint debugging.

Redis key conventions (cross-language, must stay identical):

| Key | Database | Written by | Read by |
|---|---|---|---|
| `train:metrics:{taskId}` | `database 5` (TTL 7 days) | Python | Java `GET /api/train/{id}/metrics` |
| `train:cancel:{taskId}` | metrics db (TTL 1 day) | Python (when it receives a Java cancel request) | Python worker, polled each epoch |
| `train:running:{taskId}` | broker db | Python training lock (SET NX) | Python |
| `train:queue` | broker db | Java (`mode=redis` only) | Celery |

Import/export run on a background thread pool: `DatasetJobRegistry` holds a per-dataset mutex, and while it is held, deleting the
dataset, adding/removing images, labels or annotations, and re-triggering an import or export are all rejected.

## 9. Running locally

```bash
# 1. Prepare the MySQL schema: apply src/main/resources/db/schema.sql (idempotent, includes CREATE DATABASE)
#    mysql -h <host> -P <port> -u <user> -p < src/main/resources/db/schema.sql
# 2. Override configuration for your environment: edit application.yml, or use startup args such as --spring.datasource.url=

mvn clean package -DskipTests
java -jar target/yolo-manager.jar            # port 8080

# Let the training service call back when deploying across hosts
java -jar target/yolo-manager.jar --YOLO_CALLBACK_BASE_URL=http://<this-host-ip>:8080
# Use the production profile
java -jar target/yolo-manager.jar --spring.profiles.active=prod
```

The artifact under the module's `target/` directory is named `yolo-manager.jar` (set via `finalName`). There is no `mvnw` in this
module, so a local Maven ≥ 3.8 is required.

If you would rather not install a JDK/Maven, use the root `docker-compose.yml` directly (service name `manager`):
[Dockerfile](Dockerfile) is a multi-stage build that runs `mvn package` inside the container, with all configuration injected by
compose through environment variables.

## 10. Caveats and current state

1. **No authentication + CORS wide open**: `WebMvcConfig` allows every origin and method for `/**`, so the API is callable by anyone.
   Keep it on an intranet or behind a gateway.
2. **Plaintext configuration**: `application.yml` and `.env.example` contain real intranet addresses and credentials; replace them with
   placeholders before publishing.
3. `GET /api/train/{id}/metrics` only reads the Redis cache (key `train:metrics:{taskId}`, TTL 7 days) and returns an empty array once it
   expires; the `train_task` table does **not** store per-epoch metrics (only status/progress). To keep curves long-term, rebuild them from
   the `results.csv` archived to storage (callback field `resultsCsvKey`); the final mAP@50 / mAP@50-95 live in `model_version`.
4. Training reads the annotation files **already exported to storage**; training without calling `POST /api/dataset/{id}/export` first will fail.
5. Containerised deployment is ready: this module has a [Dockerfile](Dockerfile) (maven:3.9-eclipse-temurin-17 build +
   eclipse-temurin:17-jre runtime), the orchestration file is the root `docker-compose.yml`, and the one-command scripts are
   `deploy.sh` / `deploy.ps1` (English: `deploy_en.sh` / `deploy_en.ps1`). See
   [One-command deployment (Docker)](../README_EN.md#one-command-deployment-docker-recommended).
6. Images contain no environment-specific credentials; the intranet addresses in `application.yml` are local-development defaults only and
   are fully overridden by environment variables in the Docker deployment.
