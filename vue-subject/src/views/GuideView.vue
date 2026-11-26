<template>
  <div class="guide-page">
    <el-card shadow="never" class="guide-header">
      <h2>{{ $t('YOLO 视觉识别模型管理平台 · 使用指南') }}</h2>
      <p class="sub">{{ $t('从数据集准备到模型训练，再到对外接口识别的完整操作说明') }}</p>
    </el-card>

    <el-row :gutter="16">
      <!-- 左侧目录 -->
      <el-col :span="5">
        <el-card shadow="never" class="guide-nav">
          <div
            v-for="item in navItems"
            :key="item.id"
            class="nav-item"
            :class="{ active: activeSection === item.id }"
            @click="scrollTo(item.id)"
          >{{ $t(item.title) }}</div>
        </el-card>
      </el-col>

      <!-- 右侧正文 -->
      <el-col :span="19">
        <!-- 1. 整体流程 -->
        <el-card id="sec-flow" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('一、整体流程') }}</span></template>
          <el-steps :active="6" finish-status="success" align-center class="flow-steps">
            <el-step :title="$t('创建数据集')" :description="$t('数据集管理 → 新建')" />
            <el-step :title="$t('导入 / 上传图片')" :description="$t('zip 导入或批量上传')" />
            <el-step :title="$t('在线标注')" :description="$t('无标注图片画框打标')" />
            <el-step :title="$t('导出标注')" :description="$t('生成 YOLO 训练文件')" />
            <el-step :title="$t('训练模型')" :description="$t('新建 / 继续训练')" />
            <el-step :title="$t('激活与调用')" :description="$t('上线模型 + 对外接口')" />
          </el-steps>
          <el-alert type="info" :closable="false" show-icon style="margin-top:16px"
            :title="$t('关键提醒：训练读取的是「导出到存储」的标注文件，在线标注完成后必须先执行「导出标注」，否则训练会因找不到标注而失败。')" />
          <p class="para">
            {{ $t('平台左侧菜单依次为：') }}<b>{{ $t('数据集管理') }}</b>{{ $t('（图片与标注）、') }}<b>{{ $t('训练任务') }}</b>{{ $t('（发起与监控训练）、') }}
            <b>{{ $t('模型版本') }}</b>{{ $t('（训练产物与上传权重管理、按系列折叠、激活上线、调用测试、继续训练、删除）、') }}<b>{{ $t('基础模型') }}</b>{{ $t('（官方预训练权重目录）。') }}
            {{ $t('典型路径：数据集管理 → 训练任务 → 模型版本 → 对外接口。') }}
          </p>
        </el-card>

        <!-- 2. 导入或上传图片 -->
        <el-card id="sec-import" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('二、如何导入或上传图片') }}</span></template>

          <h4>{{ $t('方式一：批量上传图片（无标注，后续在线标注）') }}</h4>
          <ol class="para">
            <li>{{ $t('进入「数据集管理」，点击「新建数据集」填写名称（可先创建标签，也可后续在标注页创建）。') }}</li>
            <li>{{ $t('在数据集行点击「图片管理」，打开图片管理对话框。') }}</li>
            <li>{{ $t('点击「上传图片」，支持一次选择多张图片，格式支持') }} <code>jpg / jpeg / png / bmp / webp</code>。</li>
            <li>{{ $t('上传完成后点击「在线标注」逐张画框打标（见第三节）。') }}</li>
          </ol>

          <h4>{{ $t('方式二：zip 压缩包导入（推荐，可带标注）') }}</h4>
          <ol class="para">
            <li>{{ $t('在数据集行点击「导入数据」，选择导入格式（默认「自动识别」，按 COCO → YOLO → 纯图片顺序探测）。') }}</li>
            <li>{{ $t('选择') }} <code>.zip</code> {{ $t('压缩包，点击「开始导入」。导入在') }}<b>{{ $t('后台异步执行') }}</b>，{{ $t('对话框内实时显示进度，支持数千张图 / 数 GB 的大数据集。') }}</li>
            <li>{{ $t('导入期间该数据集禁止上传 / 删除图片，完成前请勿操作；关闭对话框后后台仍会继续执行。') }}</li>
          </ol>

          <h4>{{ $t('导入格式要求') }}</h4>
          <el-table :data="formatRows" border size="small" style="margin-bottom:14px">
            <el-table-column prop="fmt" :label="$t('格式')" width="110" />
            <el-table-column prop="desc" :label="$t('要求')" />
          </el-table>

          <p class="para"><b>{{ $t('YOLO 格式 zip 推荐目录结构') }}</b>{{ $t('（train/val/test 划分会被自动识别，不同划分的同名文件不会互相覆盖）：') }}</p>
          <pre class="code-block">dataset.zip
├── train/
│   ├── images/          # {{ $t('训练图片（jpg/jpeg/png/bmp/webp）') }}
│   │   ├── 0001.jpg
│   │   └── 0002.jpg
│   └── labels/          # {{ $t('与图片同名的 .txt 标注') }}
│       ├── 0001.txt
│       └── 0002.txt
├── val/
│   ├── images/
│   └── labels/
└── classes.txt          # {{ $t('类别名清单，每行一个，顺序即类别 ID（也可用 data.yaml 的 names）') }}</pre>
          <p class="para">
            {{ $t('每个') }} <code>.txt</code> {{ $t('每行一个目标：') }}<code>class_id x_center y_center width height</code>，
            {{ $t('后四个值为') }}<b>{{ $t('相对图片宽高的归一化值（0~1）') }}</b>{{ $t('。例如') }} <code>0 0.5125 0.4360 0.2250 0.1780</code>。
            {{ $t('未提供 classes.txt / data.yaml 时，类别名回退为') }} <code>class_0</code>、<code>class_1</code>{{ $t('…，建议始终提供类别名清单。') }}
          </p>
          <p class="para">
            <b>{{ $t('COCO 格式') }}</b>：{{ $t('zip 内含一个包含') }} <code>images / annotations / categories</code> {{ $t('的 JSON 文件，') }}
            bbox {{ $t('为') }} <code>[x, y, w, h]</code> {{ $t('绝对像素坐标，平台自动转换为 YOLO 归一化格式并创建标签。') }}
            {{ $t('若 zip 内有多个 JSON，可在「JSON 路径」中指定相对路径（如') }} <code>annotations/instances_default.json</code>{{ $t('）。') }}
          </p>
          <p class="para"><b>{{ $t('纯图片格式') }}</b>：{{ $t('zip 内只有图片（可含子目录），导入后需在线标注。') }}</p>
        </el-card>

        <!-- 3. 在线标注 -->
        <el-card id="sec-annotate" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('三、在线标注（可选）') }}</span></template>
          <ol class="para">
            <li>{{ $t('数据集行点击「在线标注」进入标注页，左侧为图片列表，右侧为画布。') }}</li>
            <li>{{ $t('先在标签区创建 / 选择类别标签，然后在图片上按住鼠标拖拽画框，框选目标后保存。') }}</li>
            <li>{{ $t('已有标注可点击选中后调整或删除；切换图片前请确认当前标注已保存。') }}</li>
            <li>{{ $t('全部标注完成后，回到数据集列表执行「导出标注」（见下节）。') }}</li>
          </ol>
        </el-card>

        <!-- 4. 导出标注 -->
        <el-card id="sec-export" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('四、导出标注（训练前必做）') }}</span></template>
          <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px"
            :title="$t('训练服务只读取导出到存储的 YOLO 标注文件，不直接读数据库中的标注。只标注不导出 = 训练时无标注。')" />
          <ol class="para">
            <li>{{ $t('数据集行点击「导出标注」，确认后后台开始执行，对话框显示进度（已处理 / 已导出张数）。') }}</li>
            <li>{{ $t('导出将每张图片的标注写为 YOLO 格式') }} <code>.txt</code> {{ $t('存入存储的') }} <code>labels/</code> {{ $t('目录。') }}</li>
            <li>{{ $t('大数据集导出耗时较长（约每秒 7~8 张），期间禁止删除数据集或修改标注。') }}</li>
            <li><b>{{ $t('修改 / 新增标注后需要重新导出') }}</b>，{{ $t('训练才能用到最新标注。') }}</li>
          </ol>
        </el-card>

        <!-- 5. 训练 -->
        <el-card id="sec-train" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('五、如何开始训练及继续训练') }}</span></template>

          <h4>{{ $t('发起新训练') }}</h4>
          <ol class="para">
            <li>{{ $t('确认数据集已有图片、标签，且') }}<b>{{ $t('已完成导出标注') }}</b>{{ $t('。') }}</li>
            <li>{{ $t('进入「训练任务」，点击「创建训练任务」，填写：') }}
              <ul>
                <li><b>{{ $t('数据集') }}</b>：{{ $t('选择目标数据集；') }}</li>
                <li><b>{{ $t('模型类型') }}</b>：{{ $t('「基础模型」从官方预训练权重开始（在「基础模型」页维护权重目录）；「已训练模型」则基于平台已有版本续训；') }}</li>
                <li><b>{{ $t('训练轮数 epochs') }}</b>：1~1000，{{ $t('轮数越多耗时越长，一般先小规模试跑再正式训练；') }}</li>
                <li><b>{{ $t('批次大小 batch') }}</b>：1~256，{{ $t('受显存限制，过大可能训练失败；') }}</li>
                <li><b>{{ $t('输入尺寸 imgsz') }}</b>：32~4096（{{ $t('步长 32') }}），{{ $t('常用 640。') }}</li>
              </ul>
            </li>
            <li>{{ $t('提交后任务进入队列，由训练服务执行；列表可查看状态、实时日志与 mAP 指标曲线。') }}</li>
            <li>{{ $t('训练成功后') }}<b>{{ $t('自动注册新的模型版本') }}</b>{{ $t('（含 best.pt / last.pt 权重与 mAP 指标）；') }}{{ $t('基础模型初训会开启一个新系列并从 v1.0.0 起版，在「模型版本」页按系列折叠展示。') }}</li>
          </ol>

          <h4>{{ $t('继续训练（基于已有模型）') }}</h4>
          <ol class="para">
            <li>{{ $t('入口一：「模型版本」列表行点击「') }}<b>{{ $t('继续训练') }}</b>」，{{ $t('自动跳转训练页并预填「已训练模型 + 源版本」，只需再选数据集与轮数等参数。') }}</li>
            <li>{{ $t('入口二：「训练任务」创建对话框中，模型类型选「已训练模型」，再选择源版本。') }}</li>
            <li>{{ $t('续训从源版本的') }} <code>last.pt</code> {{ $t('接续训练；新版本号在本系列内递增且不重复，可设置升级方式：默认') }} <b>+0.1</b>{{ $t('（如 v1.0.0 → v1.1.0），可选') }} <b>+1.0</b>{{ $t('（如 v1.1.0 → v2.0.0）。') }}</li>
            <li>{{ $t('创建对话框会实时展示本次的') }}<b>{{ $t('预期版本') }}</b>{{ $t('；若同系列已有进行中的任务占用了该号，会自动往后递增，不会撞号。') }}</li>
            <li>{{ $t('注意：源版本被进行中的续训任务引用时不能删除；删除模型版本时，仅当没有其他版本共享同一权重文件才会删除存储中的权重。') }}</li>
          </ol>
        </el-card>

        <!-- 6. 接口调用 -->
        <el-card id="sec-api" shadow="never" class="guide-section">
          <template #header><span class="sec-title">{{ $t('六、如何通过接口调用模型进行识别') }}</span></template>

          <h4>{{ $t('1. 设置默认模型') }}</h4>
          <p class="para">
            {{ $t('在「模型版本」页对目标版本点击「') }}<b>{{ $t('激活上线') }}</b>」。{{ $t('对外识别接口不指定模型时，默认使用当前上线版本。') }}
            {{ $t('每个模型版本有唯一的 32 位') }} <b>UUID</b>{{ $t('（列表可复制），用于在接口中显式指定模型。') }}
          </p>

          <h4>{{ $t('2. 识别接口') }}</h4>
          <pre class="code-block">POST {{ apiBase }}/api/inference/detect
Content-Type: multipart/form-data

{{ $t('参数：') }}
  file       {{ $t('必填，图片文件（jpg/jpeg/png/bmp/webp）') }}
  modelUuid  {{ $t('可选，32 位模型 UUID；不传则使用当前上线的默认模型') }}</pre>

          <p class="para">{{ $t('curl 示例（调用默认模型）：') }}</p>
          <pre class="code-block">curl -X POST "{{ apiBase }}/api/inference/detect" \
  -F "file=@test.jpg"</pre>
          <p class="para">{{ $t('指定模型版本：') }}</p>
          <pre class="code-block">curl -X POST "{{ apiBase }}/api/inference/detect" \
  -F "file=@test.jpg" \
  -F "modelUuid=51e61ff5xxxxxxxxxxxxxxxxxxxxxxxx"</pre>

          <h4>{{ $t('3. 响应说明') }}</h4>
          <pre class="code-block">{
  "code": 200,
  "message": "success",
  "data": {
    "detections": [
      {
        "label": "crack",          // {{ $t('类别名称') }}
        "labelId": 0,              // {{ $t('类别 ID') }}
        "confidence": 0.87,        // {{ $t('置信度 0~1') }}
        "x1": 120.5, "y1": 80.2,   // {{ $t('检测框左上角（像素）') }}
        "x2": 340.0, "y2": 260.9   // {{ $t('检测框右下角（像素）') }}
      }
    ],
    "inferenceTimeMs": 45,          // {{ $t('推理耗时（毫秒）') }}
    "annotatedImageBase64": "data:image/jpeg;base64,...",  // {{ $t('标注图，可直接作 img src') }}
    "annotatedImageUrl": "/api/storage/yolo-tests/xxx/annotated.jpg", // {{ $t('标注图代理 URL') }}
    "imageWidth": 1600,             // {{ $t('原图宽（像素）') }}
    "imageHeight": 1184,            // {{ $t('原图高（像素）') }}
    "modelUuid": "51e61ff5...",     // {{ $t('本次使用的模型 UUID') }}
    "modelVersion": "v0.1.1"        // {{ $t('本次使用的模型版本号') }}
  }
}</pre>
          <p class="para">
            {{ $t('标注图同时提供 Base64 与 URL 两种形式，任选其一展示；未检测到目标时') }} <code>detections</code> {{ $t('为空数组，标注图为原图。') }}
          </p>

          <h4>{{ $t('4. 查询可用模型') }}</h4>
          <pre class="code-block">GET {{ apiBase }}/api/inference/models</pre>
          <p class="para">{{ $t('返回全部模型版本（含 uuid、版本号、mAP 指标、是否上线），用于获取可指定的') }} <code>modelUuid</code>。</p>

          <h4>{{ $t('5. 在线接口文档（Swagger）') }}</h4>
          <p class="para">
            {{ $t('Java 服务提供 Swagger 在线文档与调试界面（地址随当前部署自动生成）：') }}
            <code>{{ apiBase }}/swagger-ui/index.html</code>，
            {{ $t('其中「对外推理识别」分组即上述接口。') }}
          </p>

          <h4>{{ $t('6. 页面内快速验证') }}</h4>
          <p class="para">
            {{ $t('也可在「模型版本」行点击「调用测试」，上传图片后页面直接展示识别结果，') }}
            {{ $t('支持「前端画框」与「服务端标注图」两种视图切换，适合上线前快速验证模型效果。') }}
          </p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { t } from '@/i18n'

const apiBase = computed(() => window.location.origin)

const navItems = computed(() => [
  { id: 'sec-flow', title: t('一、整体流程') },
  { id: 'sec-import', title: t('二、导入 / 上传图片') },
  { id: 'sec-annotate', title: t('三、在线标注') },
  { id: 'sec-export', title: t('四、导出标注') },
  { id: 'sec-train', title: t('五、训练与继续训练') },
  { id: 'sec-api', title: t('六、接口调用识别') }
])

const formatRows = computed(() => [
  { fmt: t('自动识别'), desc: t('默认选项。按 COCO JSON → YOLO TXT → 纯图片的顺序自动探测压缩包内容。') },
  { fmt: 'YOLO TXT', desc: t('图片与同名 .txt 标注一一对应；每行 class_id xc yc w h（归一化 0~1）；类别名取 classes.txt / data.yaml names；支持 train/val/test 划分目录。') },
  { fmt: 'COCO JSON', desc: t('zip 内含 images/annotations/categories 的 JSON；bbox [x,y,w,h] 绝对像素自动转 YOLO 格式；可指定 JSON 相对路径。') },
  { fmt: t('纯图片'), desc: t('仅导入图片（jpg/jpeg/png/bmp/webp），不含标注，导入后需在线标注。') }
])

const activeSection = ref('sec-flow')

function scrollTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 滚动时高亮当前所在章节
let ticking = false
function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    ticking = false
    let current = navItems.value[0].id
    for (const item of navItems.value) {
      const el = document.getElementById(item.id)
      if (el && el.getBoundingClientRect().top <= 120) current = item.id
    }
    activeSection.value = current
  })
}

onMounted(() => {
  // 内容滚动容器是 el-main（.layout-main），而非 window
  const scroller = document.querySelector('.layout-main')
  scroller?.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})
onBeforeUnmount(() => {
  document.querySelector('.layout-main')?.removeEventListener('scroll', onScroll)
})
</script>

<style scoped>
.guide-page {
  max-width: 1200px;
  margin: 0 auto;
}
.guide-header h2 {
  margin: 0 0 6px;
}
.guide-header .sub {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
.guide-nav {
  position: sticky;
  top: 0;
}
.nav-item {
  padding: 9px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  transition: all 0.15s;
}
.nav-item:hover {
  background: #f0f2f5;
}
.nav-item.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}
.guide-section {
  margin-bottom: 16px;
  scroll-margin-top: 12px;
}
.sec-title {
  font-size: 16px;
  font-weight: 600;
}
.guide-section h4 {
  margin: 18px 0 8px;
  font-size: 14px;
}
.guide-section h4:first-of-type {
  margin-top: 0;
}
.para {
  font-size: 13px;
  line-height: 1.9;
  color: #303133;
  margin: 8px 0;
}
.para code,
li code {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 12px;
  color: #c7254e;
}
ol.para,
ul {
  padding-left: 22px;
}
.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  padding: 12px 14px;
  font-size: 12px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre;
  margin: 8px 0;
}
.flow-steps {
  margin-top: 8px;
}
</style>