<template>
  <div>
    <el-card shadow="never">
      <div class="page-header">
        <div>
          <h3>{{ $t('数据集管理') }}</h3>
          <span class="sub">{{ $t('上传图片、创建标签、导出 YOLO 格式标注') }}</span>
        </div>
        <el-button type="primary" @click="showCreate = true">
          <el-icon style="margin-right:4px"><Plus /></el-icon>{{ $t('创建数据集') }}
        </el-button>
      </div>

      <el-table :data="datasets" v-loading="loading" style="margin-top:16px">
        <!-- 伪 ID：从 1 开始的序号，真实 id 隐藏 -->
        <el-table-column type="index" label="ID" width="70" />
        <el-table-column prop="name" :label="$t('名称')" min-width="160" />
        <el-table-column prop="description" :label="$t('描述')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="$t('训练集比例')" width="100">
          <template #default="{ row }">{{ ((row.trainRatio || 0.8) * 100).toFixed(0) }}%</template>
        </el-table-column>
        <el-table-column :label="$t('图片数')" width="90">
          <template #default="{ row }">{{ row.imageCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column :label="$t('标注情况')" width="185">
          <template #default="{ row }">
            <el-tag v-if="(row.imageCount ?? 0) === 0" type="info" size="small">{{ $t('无图片') }}</el-tag>
            <el-tag v-else-if="(row.annotatedCount ?? 0) >= (row.imageCount ?? 0)" type="success" size="small">
              {{ $t('已标注 {a} / 未标注 0', { a: row.annotatedCount ?? 0 }) }}
            </el-tag>
            <el-tag v-else type="danger" size="small">
              {{ $t('已标注 {a} / 未标注 {u}', { a: row.annotatedCount ?? 0, u: (row.imageCount ?? 0) - (row.annotatedCount ?? 0) }) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('状态')" width="230">
          <template #default="{ row }">
            <el-tag v-if="row.jobKind" type="warning" effect="dark">{{ $t('{label}中…', { label: busyLabel(row.jobKind) }) }}</el-tag>
            <el-tag v-else :type="row.status === 'READY' ? 'success' : 'warning'">
              {{ row.status === 'READY' ? $t('已就绪') : $t('建设中') }}
            </el-tag>
            <el-progress
              v-if="showRowProgress(row)"
              class="row-progress"
              :percentage="rowPercent(row.id)"
              :status="rowBarStatus(row.id)"
              :indeterminate="rowIndeterminate(row.id)"
              :striped="true"
              :duration="2"
              :stroke-width="10"
              :format="() => rowProgressText(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="$t('操作')" width="440">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">{{ $t('图片管理') }}</el-button>
            <el-button size="small" :disabled="!!row.jobKind" :title="row.jobKind ? busyBlockTip(row.jobKind) : ''" @click="openImport(row)">{{ $t('导入数据') }}</el-button>
            <el-button size="small" type="primary" @click="goAnnotate(row)">{{ $t('在线标注') }}</el-button>
            <el-button size="small" type="warning" :disabled="!!row.jobKind" :title="row.jobKind ? busyBlockTip(row.jobKind) : ''" @click="openExport(row)">
              {{ $t('导出标注') }}
            </el-button>
            <el-button
              size="small"
              type="danger"
              :disabled="!!row.jobKind || (row.imageCount ?? 0) > 0"
              :title="row.jobKind ? busyBlockTip(row.jobKind) : ((row.imageCount ?? 0) > 0 ? $t('请先删除所有图片') : '')"
              :loading="deletingDatasetId === row.id"
              @click="removeDataset(row)"
            >
              {{ $t('删除') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 图片管理抽屉 -->
    <el-drawer v-model="drawerVisible" :title="`${$t('图片管理')} - ${current?.name || ''}`" size="55%">
      <div class="drawer-body">
        <el-alert
          v-if="currentBusy"
          type="warning"
          :closable="false"
          show-icon
          :title="$t('该数据集正在进行{label}，完成前禁止上传图片或删除图片/标注', { label: busyLabel(currentBusy) })"
        />
        <el-upload
          :auto-upload="true"
          :show-file-list="false"
          multiple
          accept="image/*"
          :http-request="handleUpload"
        >
          <el-button type="primary" :disabled="!!currentBusy"><el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('上传图片') }}</el-button>
        </el-upload>

        <div class="image-grid" v-loading="imagesLoading">
          <div v-for="img in images" :key="img.id" class="image-item">
            <el-image
              :src="img.url"
              :preview-src-list="images.map(i => i.url)"
              fit="cover"
              class="image-preview"
            />
            <div class="image-meta">
              <span class="file-name" :title="img.fileName">{{ img.fileName }}</span>
              <el-button type="danger" size="small" text :disabled="!!currentBusy" :loading="deletingId === img.id" @click="removeImage(img)">
                {{ $t('删除') }}
              </el-button>
            </div>
          </div>
          <el-empty v-if="!imagesLoading && images.length === 0" :description="$t('暂无图片，请上传')" />
        </div>
      </div>
    </el-drawer>

    <!-- 创建数据集对话框 -->
    <el-dialog v-model="showCreate" :title="$t('创建数据集')" width="460px">
      <el-form :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item :label="$t('名称')" prop="name">
          <el-input v-model="createForm.name" :placeholder="$t('请输入数据集名称')" />
        </el-form-item>
        <el-form-item :label="$t('描述')" prop="description">
          <el-input v-model="createForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item :label="$t('训练集比例')" prop="trainRatio">
          <el-input-number v-model="createForm.trainRatio" :min="0.1" :max="0.95" :step="0.05" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">{{ $t('取消') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">{{ $t('创建') }}</el-button>
      </template>
    </el-dialog>

    <!-- 导入数据对话框 -->
    <el-dialog v-model="showImport" :title="`${$t('导入数据')} - ${importTarget?.name || ''}`" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:14px"
        :title="$t('支持将图片与标注打包为 .zip 导入。COCO：含 annotations/categories 的 json；YOLO：图片与同名 .txt 标注一一对应（可含 classes.txt/data.yaml 定义类别名）；纯图片：仅导入图片。默认自动识别。')"
      />
      <el-form label-width="90px">
        <el-form-item :label="$t('导入格式')">
          <el-radio-group v-model="importForm.format">
            <el-radio-button value="AUTO">{{ $t('自动识别') }}</el-radio-button>
            <el-radio-button value="COCO">COCO JSON</el-radio-button>
            <el-radio-button value="YOLO">YOLO TXT</el-radio-button>
            <el-radio-button value="IMAGES">{{ $t('纯图片') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="importForm.format === 'COCO' || importForm.format === 'AUTO'" :label="$t('JSON 路径')">
          <el-input
            v-model="importForm.jsonPath"
            :placeholder="$t('可选，zip 内 COCO json 相对路径，如 annotations/instances_default.json')"
          />
        </el-form-item>
        <el-form-item :label="$t('选择文件')">
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".zip"
            :file-list="importFileList"
            :on-change="onImportFileChange"
            :on-remove="onImportFileRemove"
            :on-exceed="onImportExceed"
          >
            <el-button><el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('选择 zip 压缩包') }}</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <div v-if="importProgress && importProgress.status !== 'NONE'" class="import-progress">
        <el-progress
          :percentage="importPercent"
          :status="importBarStatus"
          :indeterminate="importProgress.status === 'PENDING'"
          :duration="2"
        />
        <p class="import-progress-text">{{ importProgressText }}</p>
      </div>
      <template #footer>
        <el-button @click="showImport = false">{{ importing ? $t('后台运行') : $t('关闭') }}</el-button>
        <el-button type="primary" :loading="importing" :disabled="!importFile" @click="submitImport">{{ $t('开始导入') }}</el-button>
      </template>
    </el-dialog>

    <!-- 导出标注对话框（异步 + 进度） -->
    <el-dialog v-model="showExport" :title="`${$t('导出标注')} - ${exportTarget?.name || ''}`" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:14px"
        :title="$t('将数据集全部标注导出为 YOLO 格式 .txt 并写入存储 labels/ 目录。导出在后台执行，完成前请勿删除数据集或修改标注。')"
      />
      <div v-if="exportProgress && exportProgress.status !== 'NONE'" class="import-progress">
        <el-progress
          :percentage="exportPercent"
          :status="exportBarStatus"
          :indeterminate="exportProgress.status === 'PENDING'"
          :duration="2"
        />
        <p class="import-progress-text">{{ exportProgressText }}</p>
      </div>
      <template #footer>
        <el-button @click="showExport = false">{{ exporting ? $t('后台运行') : $t('关闭') }}</el-button>
        <el-button type="primary" :loading="exporting" @click="submitExport">{{ $t('开始导出') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { onMounted, onBeforeUnmount, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload } from '@element-plus/icons-vue'
import { createDataset, listDatasets, uploadImage, deleteImage, listImages, exportLabels, getExportStatus, deleteDataset, importDataset, getImportStatus } from '@/api/dataset'
import { t } from '@/i18n'

const router = useRouter()
const datasets = ref([])
const loading = ref(false)

const showCreate = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', description: '', trainRatio: 0.8 })
const createRules = computed(() => ({ name: [{ required: true, message: t('请输入数据集名称'), trigger: 'blur' }] }))

const drawerVisible = ref(false)
const current = ref(null)
const images = ref([])
const imagesLoading = ref(false)
const deletingId = ref(null)
const exportingId = ref(null)
const deletingDatasetId = ref(null)

/* ---------- 忙碌（后台任务占用）展示与拦截 ---------- */
const BUSY_LABELS = { IMPORT: '导入', EXPORT: '导出', UPLOAD: '上传' }
const busyLabel = (kind) => t(BUSY_LABELS[kind] || '处理')
const busyBlockTip = (kind) => t('该数据集正在进行{label}，完成前禁止此操作', { label: busyLabel(kind) })
// 抽屉内当前数据集的忙碌类型（取自列表最新行的 jobKind，随 load 刷新）；未忙碌为 null
const currentBusy = computed(() => {
  const id = current.value?.id
  if (!id) return null
  const row = datasets.value.find((d) => d.id === id)
  return row?.jobKind || null
})

/* ---------- 列表内后台任务进度条（导入 / 导出） ---------- */
const rowProgressMap = reactive({})
const PROGRESS_API = { IMPORT: getImportStatus, EXPORT: getExportStatus }
const showRowProgress = (row) => (row.jobKind === 'IMPORT' || row.jobKind === 'EXPORT') && !!rowProgressMap[row.id]
const rowPercent = (id) => {
  const p = rowProgressMap[id]
  if (!p) return 0
  if (p.status === 'DONE') return 100
  if (!p.totalImages) return 5
  return Math.min(99, Math.round(((p.processedImages || 0) / p.totalImages) * 100))
}
const rowBarStatus = (id) => {
  const s = rowProgressMap[id]?.status
  if (s === 'DONE') return 'success'
  if (s === 'FAILED') return 'exception'
  return undefined
}
const rowIndeterminate = (id) => rowProgressMap[id]?.status === 'PENDING'
const rowProgressText = (id) => {
  const p = rowProgressMap[id]
  if (!p) return ''
  if (p.status === 'PENDING') return t('排队中')
  if (p.status === 'DONE') return t('完成')
  if (p.status === 'FAILED') return t('失败')
  return `${p.processedImages || 0}/${p.totalImages || 0}`
}
// 拉取所有忙碌（导入/导出）行的细粒度进度，存入 rowProgressMap；非忙碌行条目自动清除
const refreshRowProgress = async () => {
  const busy = datasets.value.filter((d) => d.jobKind === 'IMPORT' || d.jobKind === 'EXPORT')
  const ids = new Set(busy.map((d) => d.id))
  Object.keys(rowProgressMap).forEach((k) => { if (!ids.has(Number(k))) delete rowProgressMap[k] })
  await Promise.all(
    busy.map(async (d) => {
      const api = PROGRESS_API[d.jobKind]
      if (!api) return
      try { rowProgressMap[d.id] = await api(d.id) } catch (e) { /* 单次忽略 */ }
    })
  )
}

const removeDataset = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('确认删除数据集“{name}”？该操作不可恢复。', { name: row.name }),
      t('提示'),
      { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') }
    )
  } catch {
    return
  }
  deletingDatasetId.value = row.id
  try {
    await deleteDataset(row.id)
    ElMessage.success(t('删除成功'))
    load()
  } finally {
    deletingDatasetId.value = null
  }
}

const load = async () => {
  loading.value = true
  try {
    datasets.value = await listDatasets()
  } finally {
    loading.value = false
  }
  ensureListPolling()
}

/* 有数据集处于忙碌（jobKind 非空）时，轮询列表以及时刷新状态；全部空闲则停止 */
let listTimer = null
const stopListPolling = () => { if (listTimer) { clearInterval(listTimer); listTimer = null } }
const ensureListPolling = () => {
  refreshRowProgress()
  const hasBusy = datasets.value.some((d) => !!d.jobKind)
  if (hasBusy && !listTimer) {
    listTimer = setInterval(async () => {
      try {
        datasets.value = await listDatasets()
        await refreshRowProgress()
      } catch (e) { /* 忽略单次失败 */ }
      if (!datasets.value.some((d) => !!d.jobKind)) stopListPolling()
    }, 2000)
  } else if (!hasBusy) {
    stopListPolling()
  }
}

const submitCreate = async () => {
  creating.value = true
  try {
    await createDataset(createForm)
    ElMessage.success(t('创建成功'))
    showCreate.value = false
    createForm.name = ''
    createForm.description = ''
    createForm.trainRatio = 0.8
    load()
  } finally {
    creating.value = false
  }
}

const openDetail = async (row) => {
  current.value = row
  drawerVisible.value = true
  imagesLoading.value = true
  try {
    images.value = await listImages(row.id)
  } finally {
    imagesLoading.value = false
  }
}

const handleUpload = async (option) => {
  try {
    await uploadImage(current.value.id, option.file)
    ElMessage.success(t('上传成功'))
    images.value = await listImages(current.value.id)
    load()
  } catch (e) {
    /* request.js 已提示 */
  }
}

const removeImage = async (img) => {
  try {
    await ElMessageBox.confirm(t('确认删除图片「{name}」？', { name: img.fileName }), t('提示'), { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') })
  } catch {
    return
  }
  deletingId.value = img.id
  try {
    await deleteImage(current.value.id, img.id)
    ElMessage.success(t('删除成功'))
    images.value = await listImages(current.value.id)
    load()
  } finally {
    deletingId.value = null
  }
}

/* ---------- 导出标注（异步 + 进度轮询） ---------- */
const showExport = ref(false)
const exporting = ref(false)
const exportTarget = ref(null)
const exportProgress = ref(null)
let exportTimer = null

const exportPercent = computed(() => {
  const p = exportProgress.value
  if (!p) return 0
  if (p.status === 'DONE') return 100
  if (!p.totalImages) return 0
  return Math.min(99, Math.round((p.processedImages / p.totalImages) * 100))
})
const exportBarStatus = computed(() => {
  const s = exportProgress.value?.status
  if (s === 'DONE') return 'success'
  if (s === 'FAILED') return 'exception'
  return undefined
})
const exportProgressText = computed(() => {
  const p = exportProgress.value
  if (!p) return ''
  if (p.status === 'PENDING') return t('已启动，等待后台处理…')
  if (p.status === 'PROCESSING') return t('导出中 {cur}/{total} 张，已写出 {out} 个标注文件', { cur: p.processedImages || 0, total: p.totalImages || 0, out: p.exportedImages || 0 })
  if (p.status === 'DONE') return p.message || t('导出完成')
  if (p.status === 'FAILED') return t('导出失败：') + (p.error || t('未知错误'))
  return ''
})
const stopExportPolling = () => { if (exportTimer) { clearInterval(exportTimer); exportTimer = null } }
const startExportPolling = () => {
  stopExportPolling()
  exportTimer = setInterval(async () => {
    try {
      const p = await getExportStatus(exportTarget.value.id)
      exportProgress.value = p
      if (p.status === 'DONE') { stopExportPolling(); exporting.value = false; ElMessage.success(p.message || t('导出完成')); load() }
      else if (p.status === 'FAILED') { stopExportPolling(); exporting.value = false; ElMessage.error(t('导出失败：') + (p.error || t('未知错误'))) }
    } catch (e) { /* 单次轮询失败忽略 */ }
  }, 2000)
}
const openExport = (row) => {
  exportTarget.value = row
  exportProgress.value = null
  exporting.value = false
  showExport.value = true
  getExportStatus(row.id).then((p) => {
    if (p && (p.status === 'PENDING' || p.status === 'PROCESSING' || p.status === 'DONE')) {
      exportProgress.value = p
      if (p.status === 'PENDING' || p.status === 'PROCESSING') { exporting.value = true; startExportPolling() }
    }
  }).catch(() => {})
}
const submitExport = async () => {
  exporting.value = true
  exportProgress.value = null
  try {
    const p = await exportLabels(exportTarget.value.id)
    exportProgress.value = p
    if (p.status === 'DONE') { exporting.value = false; ElMessage.success(p.message || t('导出完成')); load() }
    else if (p.status === 'FAILED') { exporting.value = false; ElMessage.error(t('导出失败：') + (p.error || t('未知错误'))) }
    else {
      startExportPolling()
      // 立即刷新列表：拿到 jobKind=EXPORT 后状态列才会显示进度条并启动列表轮询
      load()
    }
  } catch (e) {
    exporting.value = false
    /* request.js 已提示（如数据集正忙） */
  }
}

const goAnnotate = (row) => router.push(`/datasets/${row.id}/annotate`)

/* ---------- 导入数据（异步 + 进度轮询） ---------- */
const showImport = ref(false)
const importing = ref(false)
const importTarget = ref(null)
const importFile = ref(null)
const importFileList = ref([])
const importForm = reactive({ format: 'AUTO', jsonPath: '' })
const importProgress = ref(null)
let importTimer = null

const importPercent = computed(() => {
  const p = importProgress.value
  if (!p) return 0
  if (p.status === 'DONE') return 100
  if (!p.totalImages) return 0
  return Math.min(99, Math.round((p.processedImages / p.totalImages) * 100))
})

const importBarStatus = computed(() => {
  const s = importProgress.value?.status
  if (s === 'DONE') return 'success'
  if (s === 'FAILED') return 'exception'
  return undefined
})

const importProgressText = computed(() => {
  const p = importProgress.value
  if (!p) return ''
  if (p.status === 'PENDING') return t('已接收，等待后台处理…')
  if (p.status === 'PROCESSING') {
    const fmt = p.format ? `【${p.format}】` : ''
    return t('导入中 {fmt} {cur}/{total} 张，已写入标注 {ann} 条', { fmt, cur: p.processedImages || 0, total: p.totalImages || 0, ann: p.annotationCount || 0 })
  }
  if (p.status === 'DONE') return p.message || t('导入完成')
  if (p.status === 'FAILED') return t('导入失败：') + (p.error || t('未知错误'))
  return ''
})

const stopPolling = () => {
  if (importTimer) { clearInterval(importTimer); importTimer = null }
}

const openImport = (row) => {
  importTarget.value = row
  importFile.value = null
  importFileList.value = []
  importForm.format = 'AUTO'
  importForm.jsonPath = ''
  importProgress.value = null
  showImport.value = true
  // 打开时若后台已有进度（如刷新前启动），恢复展示
  getImportStatus(row.id).then((p) => {
    if (p && (p.status === 'PENDING' || p.status === 'PROCESSING' || p.status === 'DONE')) {
      importProgress.value = p
      if (p.status === 'PENDING' || p.status === 'PROCESSING') startPolling()
    }
  }).catch(() => {})
}

const onImportFileChange = (file, fileList) => {
  importFile.value = file.raw
  importFileList.value = fileList.slice(-1)
}

const onImportFileRemove = () => {
  importFile.value = null
  importFileList.value = []
}

const onImportExceed = (files) => {
  importFile.value = files[0]
  importFileList.value = [{ name: files[0].name, raw: files[0] }]
}

const startPolling = () => {
  stopPolling()
  importTimer = setInterval(async () => {
    try {
      const p = await getImportStatus(importTarget.value.id)
      importProgress.value = p
      if (p.status === 'DONE') {
        stopPolling()
        importing.value = false
        ElMessage.success(p.message || t('导入完成'))
        load()
      } else if (p.status === 'FAILED') {
        stopPolling()
        importing.value = false
        ElMessage.error(t('导入失败：') + (p.error || t('未知错误')))
      }
    } catch (e) {
      /* 单次轮询失败忽略，下次重试 */
    }
  }, 2000)
}

const submitImport = async () => {
  if (!importFile.value) {
    ElMessage.warning(t('请先选择 zip 压缩包'))
    return
  }
  importing.value = true
  importProgress.value = null
  try {
    const p = await importDataset(importTarget.value.id, importFile.value, importForm.format, importForm.jsonPath || undefined)
    importProgress.value = p
    if (p.status === 'DONE') {
      importing.value = false
      ElMessage.success(p.message || t('导入完成'))
      load()
    } else if (p.status === 'FAILED') {
      importing.value = false
      ElMessage.error(t('导入失败：') + (p.error || t('未知错误')))
    } else {
      startPolling()
      // 立即刷新列表：拿到 jobKind=IMPORT 后状态列才会显示进度条并启动列表轮询
      load()
    }
  } catch (e) {
    importing.value = false
    /* request.js 已提示（如超过大小限制） */
  }
}

onBeforeUnmount(() => {
  stopPolling()
  stopExportPolling()
  stopListPolling()
})

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h3 { font-size: 18px; }
.sub { color: #909399; font-size: 13px; }
.drawer-body { display: flex; flex-direction: column; gap: 16px; }
.image-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; min-height: 120px; }
.image-item { border: 1px solid #e4e7ed; border-radius: 6px; overflow: hidden; }
.image-preview { width: 100%; height: 110px; display: block; }
.image-meta { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; font-size: 12px; }
.file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100px; }
.import-progress { margin-top: 4px; }
.row-progress { margin-top: 6px; width: 200px; }
.import-progress-text { margin: 8px 0 0; color: #606266; font-size: 13px; line-height: 1.5; }
</style>