# vue-subject

YOLO 视觉识别模型管理平台 · **前端**（Vue 3 + Element Plus + Axios + ECharts + Vite）

平台内部文档：[项目总体说明](../README.md) · [管理后端](../java-manger-subject/README.md) · [训练与推理服务](../python-yolo-subject/README.md)

[English](README_EN.md)

## 1. 技术栈

| 依赖 | 版本 |
|---|---|
| vue | ^3.5.13 |
| vue-router | ^4.4.5 |
| pinia | ^2.2.6 |
| element-plus | ^2.8.8（+ `@element-plus/icons-vue` ^2.3.1） |
| axios | ^1.7.7 |
| echarts | ^5.5.1 |
| vite / @vitejs/plugin-vue（dev） | ^5.4.11 / ^5.2.0 |

## 2. 页面与路由

`/` 使用 `layout/Layout.vue` 作为外壳（侧边栏 + 顶栏 + 面包屑），默认重定向到 `/datasets`；`router.afterEach` 把标题写成「页面名 - YOLO 模型管理平台」。

| 路径 | 名称 | 页面 | 说明 |
|---|---|---|---|
| `/datasets` | Datasets | `DatasetList.vue` | 数据集增删、图片管理、上传/zip 导入、导出标注、标签管理、进度轮询 |
| `/datasets/:id/annotate` | Annotate | `AnnotateView.vue` | 在线标注：逐张画框、选标签、保存/批量同步 |
| `/train` | Train | `TrainList.vue` | 新建训练任务（新训 / 继续训练，带预期版本提示）、状态轮询（CREATED/QUEUED/RUNNING 区分“准备数据中”）、epoch 指标曲线、取消/删除 |
| `/models` | Models | `ModelList.vue` | 模型版本按系列折叠的树表（子行为续训/同名上传版本）、上传注册、改名、激活上线、下载、删除、继续训练入口 |
| `/models/:id/test` | ModelTest | `ModelTest.vue` | 上传测试图执行推理并叠加检测框，查看历史测试记录 |
| `/base-models` | BaseModels | `BaseModelList.vue` | 基础预训练权重：官方清单同步、下载、状态刷新、启停、删除 |
| `/guide` | Guide | `GuideView.vue` | 使用指南：完整流程、导入格式要求、常见坑（站内操作手册） |

侧边栏主菜单为前四项（数据集管理 / 训练任务 / 模型版本 / 基础模型）；`/guide` 由顶栏按钮进入（`Layout.vue` 中 `$router.push('/guide')`），标注页与测试页则从对应列表行按钮跳转。

## 3. 目录结构

```
src/
├── main.js                    # 应用入口：挂载 Pinia + Router + Element Plus
├── App.vue
├── i18n/index.js              # 零依赖 i18n：t()、setLocale()、全局 $t
├── i18n/en.js                 # 英文翻译表（中文原文 -> English）
├── router/index.js            # 上述 7 条路由
├── store/app.js               # 全局 UI 状态：sidebarCollapsed、activeDatasetId、toggleSidebar
├── layout/Layout.vue          # 侧边栏 + 顶栏 + 面包屑（顶栏含语言切换）
├── api/
│   ├── request.js             # Axios 实例与 Result 解包拦截器
│   ├── dataset.js             # 数据集 / 图片 / 导入导出 / 标签 / 标注
│   ├── train.js               # 训练任务与指标
│   ├── model.js               # 模型版本、上传、激活、测试、下载
│   └── baseModel.js           # 基础模型目录
├── components/
│   └── AnnotationCanvas.vue   # 标注画布（绘制/拖拽/缩放/删除/渲染推理框）
└── views/                     # 7 个页面组件
```

## 4. 中英文切换

界面全部文案内置中英双语，**默认中文**，切换入口在 `Layout.vue` 顶栏。

- **零依赖**：`src/i18n/index.js` 自己实现 `t(key, params)`，没有引入 vue-i18n。
- **以中文原文当 key**：英文文案集中在 `src/i18n/en.js`，形如 `"中文原文": "English"` 的半展平映射；缺译时自动回退中文，不会出现空白。
- **插值**：`t('该数据集正在进行{label}，完成前禁止此操作', { label: t('导入') })`。
- **用法**：模板里写 `$t('…')`（已注册为全局属性），脚本里 `import { t } from '@/i18n'`。
- **响应式**：`$t` 在渲染期读 `locale.value`，切语言立即重渲染。在模块顶层只求值一次的文案（校验规则、导航列表、格式表）必须包成
  `computed(() => …)`，否则会停在旧语言；状态/标签映射表因此只存中文值，在调用处 `t(STATUS_TEXT[s])`。
- **Element Plus 自带文案**（分页、日期选择、空表格、上传提示等）由 `App.vue` 里的 `<el-config-provider :locale>` 跟随同一状态；
  `ElMessageBox` 不保证继承该 locale，因此弹窗显式传 `confirmButtonText` / `cancelButtonText`。
- **持久化**：选择存在 `localStorage` 的 `yolo-locale`；路由标题的 `meta.title` 直接存中文当 key，在 `router.afterEach` 里翻译。
- **有意不翻译**：Swagger/OpenAPI 标题、后端返回的 `message`（Java 原文直接透出）、Java/Python 源码本身——i18n 只覆盖前端展示层。

新增文案时，直接把中文写进 `$t()` / `t()`，并在 `src/i18n/en.js` 补上对应条目即可。

## 5. 接口约定

`api/request.js`：`baseURL = '/api'`，`timeout = 60000`。响应拦截器统一处理后端 `Result` 结构 —— `code !== 200` 时弹 `ElMessage.error(message)` 并 reject；成功时**自动解包返回 `data`**，因此页面代码里拿到的就是业务数据（无 `Result` 包裹的响应原样返回）。

调用的后端接口（全部经 `/api` 前缀）：

| 模块 | 接口 |
|---|---|
| dataset.js | `POST/GET /dataset`、`GET/DELETE /dataset/{id}`、`POST /dataset/{id}/import`、`GET /dataset/{id}/import-status`、`POST /dataset/{datasetId}/upload`、`GET/DELETE /dataset/{datasetId}/images[/{imageId}]`、`POST /dataset/{datasetId}/export`、`GET /dataset/{datasetId}/export-status`、`GET/POST/PUT/DELETE /dataset/{datasetId}/label[/{labelId}]`、`POST/GET/DELETE /annotation[...]`、`POST /annotation/sync/{imageId}` |
| train.js | `POST /train`、`GET /train`、`GET /train/{id}`、`GET /train/{id}/metrics`、`POST /train/{id}/cancel`、`DELETE /train/{id}`、`GET /train/base-models` |
| model.js | `GET /model`、`POST /model/upload`、`PUT /model/{id}/name`、`POST /model/{id}/activate`、`POST /model/{id}/test`、`GET /model/{id}/tests`、`DELETE /model/{id}`；下载直接用链接 `/api/model/{id}/download` |
| baseModel.js | `GET /base-model/list`、`POST /base-model/refresh-status`、`POST /base-model/sync-official`、`POST /base-model/download`、`POST /base-model/{id}/enabled`、`DELETE /base-model/{id}` |

图片与模型权重不走 `/api`：本地存储模式下取 `/storage/**`，S3 模式下经 `/api/storage/{bucket}/**` 代理，两条路径都已在 Vite 代理中配置。

## 6. 标注画布组件

`AnnotationCanvas.vue`

| Props | 默认值 | 说明 |
|---|---|---|
| `imageUrl` | 必填 | 底图地址 |
| `annotations` | `[]` | 已有标注框 / 推理框 |
| `width` / `height` | 800 / 600 | 原图像素尺寸，用于坐标归一化 |
| `activeLabelColor` | `#409EFF` | 当前标签颜色 |
| `editable` | `true` | 只读模式（如查看推理结果）关闭绘制与拖拽 |

| Emits | 时机 |
|---|---|
| `create` / `update` / `remove` | 新建、拖动或缩放、删除标注框 |
| `select` / `select-index` | 点选某个框，用于联动右侧列表 |

输出的框为归一化值：`xCenter`、`yCenter`、`width`、`height` 保留 6 位小数（0~1），与 YOLO `.txt` 每行 `class_id x_center y_center width height` 的语义一致。

## 7. 开发与构建

```bash
npm install
npm run dev        # 开发服务器
npm run build      # 生产构建，产物 dist/
npm run preview    # 预览构建产物
```

脚本只有 `dev` / `build` / `preview`（没有 `serve`）。`vite.config.js` 关键配置：

- `server.host = '0.0.0.0'`、`server.port = 5173`：**端口被占用时 Vite 会自动递增到 5174 等，以终端输出的 Local 地址为准**
- 代理：`/api` 与 `/storage` → `http://localhost:8080`（改后端端口时同步修改此处）
- 别名：`@` → `./src`
- 构建：`outDir: 'dist'`，`chunkSizeWarningLimit: 2000`（Element Plus + ECharts 体积较大，属预期）

### Docker / nginx 部署

[Dockerfile](Dockerfile) 为两阶段：`node:20-alpine` 跑 `npm ci && npm run build`，产物拷进 `nginx:1.27-alpine`，容器配置见 [nginx.conf](nginx.conf)：

| 路径 | 处理 |
|---|---|
| `/` | `try_files $uri $uri/ /index.html`，已解决 history 模式刷新 404 |
| `/api/`、`/storage/` | 反代 `http://manager:8080`（compose 服务名），读超时 900s、`proxy_request_buffering off` |
| `/swagger-ui/`、`/v3/api-docs` | 反代管理后端接口文档（不需要对外暴露时删掉这两段） |
| `/healthz` | 直接返回 200，供 `deploy.sh` / `deploy.ps1` 做就绪探测 |
| `client_max_body_size` | 5120m，与后端 `multipart` 上限对齐，否则大数据集 zip 先在 nginx 吃 413 |

浏览器端不需要配置任何后端地址：代码里请求的相对路径 `/api/**` 由 nginx 转发，因此**开发（Vite 代理）与容器（nginx 代理）用同一套接口路径**。对外端口在仓库根目录 `deploy.sh` / `deploy.ps1` 的【配置区】`WEB_PORT` 里改（默认 80），也可以在交互式引导的「应用对外端口」一步里直接回答（两个脚本顶部【配置区】就是引导的默认值），详见 [根 README 的“一键部署（Docker）”](../README.md#一键部署docker推荐)。

## 8. 注意事项

1. **标注完成后必须先「导出标注」再训练**：训练读的是导出到对象存储的 `labels/*.txt`，不是 MySQL 里的在线标注。
2. 图片上传格式支持 `jpg / jpeg / png / bmp / webp`；zip 导入支持 YOLO 标注包、COCO JSON 与纯图片包，大文件在后台异步处理并轮询进度。
3. 同一数据集的导入/导出期间后端会加互斥锁，相关按钮此时会返回冲突提示，属正常行为。
4. 生产环境需要 SPA 回退（history 模式）：本模块 `nginx.conf` 已用 `try_files` 处理；若不用 Docker 而是自己把 `dist/` 丢到别的静态服务器，仍需把 404 重写到 `index.html`，否则 `/models/1/test` 这类深层路由刷新会 404。
5. 前端未做任何鉴权，接口与页面均为匿名可访问（后端同样没有登录逻辑）。
6. 语言切换只影响前端界面；后端返回的错误文案与 Swagger 文档保持源语言。
7. 本模块没有 `.gitignore`，`node_modules/`、`dist/` 的忽略规则统一写在仓库根 `.gitignore`。
