# vue-subject

YOLO vision model management platform · **Frontend** (Vue 3 + Element Plus + Axios + ECharts + Vite)

[简体中文](README.md) · Internal docs: [Project overview](../README_EN.md) · [Management backend](../java-manger-subject/README_EN.md) · [Training & inference service](../python-yolo-subject/README_EN.md)

## 1. Tech stack

| Dependency | Version |
|---|---|
| vue | ^3.5.13 |
| vue-router | ^4.4.5 |
| pinia | ^2.2.6 |
| element-plus | ^2.8.8 (+ `@element-plus/icons-vue` ^2.3.1) |
| axios | ^1.7.7 |
| echarts | ^5.5.1 |
| vite / @vitejs/plugin-vue (dev) | ^5.4.11 / ^5.2.0 |

## 2. Pages and routes

`/` uses `layout/Layout.vue` as the shell (sidebar + top bar + breadcrumb) and redirects to `/datasets` by default; `router.afterEach`
sets the document title to “Page name - YOLO Model Management Platform”.

| Path | Name | Component | Description |
|---|---|---|---|
| `/datasets` | Datasets | `DatasetList.vue` | Create/delete datasets, image management, upload/zip import, annotation export, label management, progress polling |
| `/datasets/:id/annotate` | Annotate | `AnnotateView.vue` | Online annotation: draw boxes one image at a time, pick labels, save/bulk sync |
| `/train` | Train | `TrainList.vue` | New training task (fresh / continued training, with an expected-version hint), status polling (CREATED/QUEUED/RUNNING, distinguishing "preparing data"), epoch metric curves, cancel/delete |
| `/models` | Models | `ModelList.vue` | Model versions as a tree folded by series (child rows = continued-training / same-name upload versions), upload/register, rename, activate, download, delete, entry point for continued training |
| `/models/:id/test` | ModelTest | `ModelTest.vue` | Upload a test image, run inference with overlaid boxes, browse historical test records |
| `/base-models` | BaseModels | `BaseModelList.vue` | Base pretrained weights: official list sync, download, status refresh, enable/disable, delete |
| `/guide` | Guide | `GuideView.vue` | User guide: full workflow, import format requirements, common pitfalls (in-app operations manual) |

The sidebar's main menu contains the first four entries (Datasets / Training / Models / Base models); `/guide` is reached from a top-bar
button (`$router.push('/guide')` in `Layout.vue`), while the annotate and test pages are opened from the corresponding row buttons in the lists.

## 3. Layout

```
src/
├── main.js                    # app entry: mounts Pinia + Router + Element Plus
├── App.vue
├── i18n/index.js              # zero-dependency i18n: t(), setLocale(), $t global property
├── i18n/en.js                 # translation table (Chinese source text -> English)
├── router/index.js            # the 7 routes above
├── store/app.js               # global UI state: sidebarCollapsed, activeDatasetId, toggleSidebar
├── layout/Layout.vue          # sidebar + top bar + breadcrumb (holds the language switcher)
├── api/
│   ├── request.js             # Axios instance and Result-unwrapping interceptor
│   ├── dataset.js             # datasets / images / import-export / labels / annotations
│   ├── train.js               # training tasks and metrics
│   ├── model.js               # model versions, upload, activate, test, download
│   └── baseModel.js           # base model catalogue
├── components/
│   └── AnnotationCanvas.vue   # annotation canvas (draw/drag/zoom/delete, renders inference boxes)
└── views/                     # 7 page components
```

## 4. Internationalisation (Chinese / English)

The UI is bilingual, **defaulting to Chinese**, with a switcher in the top bar of `Layout.vue`.

- **Zero dependencies**: `src/i18n/index.js` implements `t(key, params)` itself; no vue-i18n is pulled in.
- **The Chinese source text is the key.** English strings live in `src/i18n/en.js`, a flat map of
  `"中文原文": "English"`. A missing entry silently falls back to Chinese, so partial translations never break the page.
- **Interpolation** uses named placeholders: `t('该数据集正在进行{label}，完成前禁止此操作', { label: t('导入') })`.
- **Usage**: templates call `$t('…')` (registered as a global property), scripts `import { t } from '@/i18n'`.
- **Reactivity**: `$t` reads `locale.value` during render, so switching the language re-renders immediately. Anything evaluated once at
  module level (validation rules, navigation lists, format tables) must be wrapped in `computed(() => …)` or it will keep the old language;
  status/label maps therefore store the Chinese value and translate at the call site (`t(STATUS_TEXT[s])`).
- **Element Plus built-in wording** (pagination, date picker, empty table, upload hints …) follows the language through
  `<el-config-provider :locale>` in `App.vue`, driven by the same state. `ElMessageBox` does not reliably inherit that locale, so dialogs
  pass `confirmButtonText` / `cancelButtonText` explicitly.
- **Persistence**: the choice is stored in `localStorage` under `yolo-locale`; route titles come from `meta.title`, which stores Chinese as
  the key and is translated in `router.afterEach`.
- **Not translated on purpose**: the Swagger/OpenAPI title, backend-produced `message` strings (returned as-is from Java), and the Java/Python
  source code itself — the i18n layer is frontend presentation only.

When adding new UI text, just write the Chinese string inside `$t()` / `t()` and add a matching entry to `src/i18n/en.js`.

## 5. API conventions

`api/request.js`: `baseURL = '/api'`, `timeout = 60000`. The response interceptor handles the backend `Result` envelope uniformly —
when `code !== 200` it raises `ElMessage.error(message)` and rejects; on success it **unwraps and returns `data`**, so page code receives
business data directly (responses without a `Result` wrapper are passed through unchanged). Error text shown by the interceptor is translated
through the same i18n layer.

Backend endpoints used (all under the `/api` prefix):

| Module | Endpoints |
|---|---|
| dataset.js | `POST/GET /dataset`, `GET/DELETE /dataset/{id}`, `POST /dataset/{id}/import`, `GET /dataset/{id}/import-status`, `POST /dataset/{datasetId}/upload`, `GET/DELETE /dataset/{datasetId}/images[/{imageId}]`, `POST /dataset/{datasetId}/export`, `GET /dataset/{datasetId}/export-status`, `GET/POST/PUT/DELETE /dataset/{datasetId}/label[/{labelId}]`, `POST/GET/DELETE /annotation[...]`, `POST /annotation/sync/{imageId}` |
| train.js | `POST /train`, `GET /train`, `GET /train/{id}`, `GET /train/{id}/metrics`, `POST /train/{id}/cancel`, `DELETE /train/{id}`, `GET /train/base-models` |
| model.js | `GET /model`, `POST /model/upload`, `PUT /model/{id}/name`, `POST /model/{id}/activate`, `POST /model/{id}/test`, `GET /model/{id}/tests`, `DELETE /model/{id}`; downloads use the plain link `/api/model/{id}/download` |
| baseModel.js | `GET /base-model/list`, `POST /base-model/refresh-status`, `POST /base-model/sync-official`, `POST /base-model/download`, `POST /base-model/{id}/enabled`, `DELETE /base-model/{id}` |

Images and model weights do not go through `/api`: local storage mode reads `/storage/**`, S3 mode goes through the `/api/storage/{bucket}/**`
proxy; both paths are configured in the Vite proxy.

## 6. Annotation canvas component

`AnnotationCanvas.vue`

| Prop | Default | Description |
|---|---|---|
| `imageUrl` | required | Base image URL |
| `annotations` | `[]` | Existing annotation boxes / inference boxes |
| `width` / `height` | 800 / 600 | Original pixel size, used for coordinate normalisation |
| `activeLabelColor` | `#409EFF` | Colour of the active label |
| `editable` | `true` | Read-only mode (e.g. viewing inference results) disables drawing and dragging |

| Emits | When |
|---|---|
| `create` / `update` / `remove` | Box created, dragged/resized, or deleted |
| `select` / `select-index` | A box is clicked, to sync the list on the right |

Emitted boxes use normalised values: `xCenter`, `yCenter`, `width`, `height` rounded to 6 decimals (0~1), matching the semantics of each
`class_id x_center y_center width height` line in a YOLO `.txt`.

## 7. Development and build

```bash
npm install
npm run dev        # dev server
npm run build      # production build into dist/
npm run preview    # preview the build output
```

The only scripts are `dev` / `build` / `preview` (there is no `serve`). Key settings in `vite.config.js`:

- `server.host = '0.0.0.0'`, `server.port = 5173`: **if the port is taken, Vite increments it (5174, …) — use the Local URL it prints**
- Proxy: `/api` and `/storage` → `http://localhost:8080` (adjust here when you change the backend port)
- Alias: `@` → `./src`
- Build: `outDir: 'dist'`, `chunkSizeWarningLimit: 2000` (Element Plus + ECharts are large by design)

### Docker / nginx deployment

[Dockerfile](Dockerfile) is a two-stage build: `node:20-alpine` runs `npm ci && npm run build`, and the output is copied into
`nginx:1.27-alpine`. Container settings, see [nginx.conf](nginx.conf):

| Path | Behaviour |
|---|---|
| `/` | `try_files $uri $uri/ /index.html`, which fixes history-mode refresh 404s |
| `/api/`, `/storage/` | Reverse proxy to `http://manager:8080` (compose service name), read timeout 900s, `proxy_request_buffering off` |
| `/swagger-ui/`, `/v3/api-docs` | Reverse proxy to the backend API docs (remove these two blocks if you don't want them public) |
| `/healthz` | Returns 200 directly, used by `deploy.sh` / `deploy.ps1` for readiness probing |
| `client_max_body_size` | 5120m, aligned with the backend `multipart` limit, otherwise large dataset zips hit 413 in nginx first |

The browser needs no backend address configuration: relative `/api/**` requests in code are forwarded by nginx, so **development (Vite proxy)
and the container (nginx proxy) use the same set of API paths**. The published port is set by `WEB_PORT` in the [configuration] block of the
root `deploy.sh` / `deploy.ps1` (default 80), and can also be answered in the interactive guide's “application public port” step (the
[configuration] blocks at the top of both scripts are the guide's defaults). See
[One-command deployment (Docker)](../README_EN.md#one-command-deployment-docker-recommended).

## 8. Caveats

1. **After annotating, export the annotations before training**: training reads the `labels/*.txt` exported to object storage, not the online
   annotations in MySQL.
2. Image upload accepts `jpg / jpeg / png / bmp / webp`; zip import supports YOLO label packages, COCO JSON and image-only archives, with large
   files processed asynchronously in the background and polled for progress.
3. The backend holds a mutex on a dataset during import/export, so the related buttons return a conflict message in that window — expected behaviour.
4. Production needs SPA fallback (history mode): this module's `nginx.conf` already handles it with `try_files`. If you skip Docker and host
   `dist/` on another static server, you must still rewrite 404 to `index.html`, or deep routes such as `/models/1/test` will 404 on refresh.
5. The frontend performs no authentication; API and pages are anonymously accessible (the backend has no login logic either).
6. The language switch only affects the frontend UI; backend error messages and Swagger documentation stay in their original language.
7. This module has no `.gitignore` of its own; `node_modules/` and `dist/` are ignored by the repository-root `.gitignore`.
