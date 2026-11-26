<template>
  <div>
    <el-card shadow="never">
      <div class="page-header">
        <div>
          <h3>{{ $t('基础模型') }}</h3>
          <span class="sub">
            {{ $t('训练表单可选的基础权重目录（数据库 base_model 表 + Python 训练端本地文件同步）；当前目录：') }}<code>{{ baseDir || ('(' + $t('未知') + ')') }}</code>
          </span>
        </div>
        <div class="actions">
          <el-button :loading="refreshing" @click="doRefresh">{{ $t('刷新已下载状态') }}</el-button>
          <el-button :loading="syncing" @click="openOfficial">{{ $t('同步官网最新') }}</el-button>
          <el-button
            type="primary"
            :loading="downloading"
            :disabled="!downloadableSelected.length"
            @click="doDownloadSelected"
          >
            {{ $t('下载选中') }} ({{ downloadableSelected.length }})
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="polling"
        type="info"
        show-icon
        :closable="false"
        style="margin-top:12px"
        :title="$t('存在 DOWNLOADING 条目，已启用 3s 轮询自动刷新；下载完成后请再次刷新以确认状态。')"
      />

      <el-table
        :data="rows"
        v-loading="loading"
        style="margin-top:16px"
        row-key="modelName"
        @selection-change="onSelChange"
      >
        <el-table-column type="selection" width="46" :selectable="row => !['DOWNLOADED','DOWNLOADING'].includes(row.status)" />
        <el-table-column prop="modelName" :label="$t('模型')" width="120" />
        <el-table-column prop="family" :label="$t('家族')" width="70" />
        <el-table-column prop="scale" :label="$t('尺度')" width="70" />
        <el-table-column prop="officialFile" :label="$t('官方文件')" width="130" />
        <el-table-column :label="$t('状态')" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('大小')" width="110">
          <template #default="{ row }">{{ row.sizeBytes ? formatBytes(row.sizeBytes) : '-' }}</template>
        </el-table-column>
        <el-table-column :label="$t('启用')" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              @change="(v) => doToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="$t('最近同步')" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSyncAt) }}</template>
        </el-table-column>
        <el-table-column prop="localPath" :label="$t('本地路径')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="$t('错误')" min-width="160">
          <template #default="{ row }">
            <el-tooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
              <span class="err">{{ row.errorMsg }}</span>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('操作')" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="['MISSING','FAILED'].includes(row.status)"
              size="small"
              type="primary"
              text
              :loading="singleDl === row.modelName"
              @click="doDownloadOne(row)"
            >{{ $t('下载') }}</el-button>
            <el-button
              size="small"
              type="danger"
              text
              :disabled="row.status === 'DOWNLOADED'"
              @click="doDelete(row)"
            >{{ $t('删除') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 官网目录弹窗：仅展示可下载的检测权重，勾选后一键入库并可选立即下载 -->
    <el-dialog v-model="officialVisible" :title="$t('同步 Ultralytics 官方基础模型目录')" width="820px">
      <div class="toolbar">
        <span class="hint">
          {{ $t('当前 GitHub Release tag：') }}<code>{{ officialTag || '—' }}</code>{{ $t('；共 {n} 项', { n: officialRows.length }) }}
          <span v-if="officialNewCount">{{ $t('，其中新增 {n} 项尚未入库', { n: officialNewCount }) }}</span>
        </span>
        <div>
          <el-checkbox v-model="onlyNew" style="margin-right:8px">{{ $t('仅显示未入库') }}</el-checkbox>
          <el-button :loading="officialLoading" size="small" @click="fetchOfficial(true)">{{ $t('重新拉取') }}</el-button>
        </div>
      </div>
      <el-table
        :data="officialVisibleRows"
        v-loading="officialLoading"
        style="margin-top:8px"
        row-key="modelName"
        @selection-change="onOfficialSel"
        max-height="420"
      >
        <el-table-column type="selection" width="46" :selectable="() => true" />
        <el-table-column prop="modelName" :label="$t('模型')" width="120" />
        <el-table-column prop="family" :label="$t('家族')" width="70" />
        <el-table-column prop="scale" :label="$t('尺度')" width="70" />
        <el-table-column prop="officialFile" :label="$t('官方文件')" width="130" />
        <el-table-column :label="$t('已入库')" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.known" size="small" type="success">{{ $t('是') }}</el-tag>
            <el-tag v-else size="small" type="info">{{ $t('否') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('当前状态')" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.currentStatus" :type="statusTagType(row.currentStatus)" size="small">
              {{ statusText(row.currentStatus) }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="downloadUrl" :label="$t('下载直链')" min-width="240" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="officialVisible = false">{{ $t('关闭') }}</el-button>
        <el-button type="primary" :loading="syncing" @click="doSyncSelected(false)">
          {{ $t('仅入库选中') }} ({{ officialSel.length }})
        </el-button>
        <el-button type="primary" :loading="downloading" @click="doSyncSelected(true)">
          {{ $t('入库并立即下载') }} ({{ officialSel.length }})
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listBaseModels,
  refreshBaseModelStatus,
  syncOfficialBaseModels,
  downloadBaseModels,
  toggleBaseModelEnabled,
  deleteBaseModel
} from '@/api/baseModel'
import { t } from '@/i18n'

const rows = ref([])
const baseDir = ref('')
const loading = ref(false)
const refreshing = ref(false)
const syncing = ref(false)
const downloading = ref(false)
const singleDl = ref('')
const selected = ref([])

const officialVisible = ref(false)
const officialLoading = ref(false)
const officialRows = ref([])
const officialTag = ref('')
const officialSel = ref([])
const onlyNew = ref(false)
const officialNewCount = computed(() => officialRows.value.filter((r) => !r.known).length)
const officialVisibleRows = computed(() =>
  onlyNew.value ? officialRows.value.filter((r) => !r.known) : officialRows.value
)

const downloadSet = new Set(['MISSING', 'FAILED'])
const downloadableSelected = computed(() =>
  selected.value.filter((r) => downloadSet.has(r.status)).map((r) => r.modelName)
)

const polling = computed(() => rows.value.some((r) => r.status === 'DOWNLOADING'))

/* ---------------- helpers ---------------- */
const STATUS_TEXT = { MISSING: '未下载', DOWNLOADING: '下载中', DOWNLOADED: '已下载', FAILED: '下载失败' }
const statusText = (s) => t(STATUS_TEXT[s] || s)
const statusTagType = (s) => ({ MISSING: 'info', DOWNLOADING: 'warning', DOWNLOADED: 'success', FAILED: 'danger' }[s] || 'info')
const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '-')
const formatBytes = (n) => {
  if (!n) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1 }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

/* ---------------- 数据加载 ---------------- */
const load = async () => {
  loading.value = true
  try {
    rows.value = await listBaseModels()
  } finally {
    loading.value = false
  }
  ensurePolling()
}

/* ---------------- 轮询：只要存在 DOWNLOADING 每 3s 刷新状态 ---------------- */
let timer = null
const stopPolling = () => { if (timer) { clearInterval(timer); timer = null } }
const ensurePolling = () => {
  if (polling.value && !timer) {
    timer = setInterval(async () => {
      try { await refreshBaseModelStatus(); rows.value = await listBaseModels() } catch { /* ignore */ }
      if (!rows.value.some((r) => r.status === 'DOWNLOADING')) stopPolling()
    }, 3000)
  } else if (!polling.value) {
    stopPolling()
  }
}

/* ---------------- 操作 ---------------- */
const doRefresh = async () => {
  refreshing.value = true
  try {
    await refreshBaseModelStatus()
    rows.value = await listBaseModels()
    // 顺便拉一下目录路径显示（本地扫描接口只在刷新时经过后端；这里从任一条目的 localPath 反推）
    const hit = rows.value.find((r) => r.localPath)
    if (hit) baseDir.value = hit.localPath.replace(/\/[^/]+$/, '')
    ElMessage.success(t('状态已刷新'))
  } finally {
    refreshing.value = false
  }
  ensurePolling()
}

const onSelChange = (v) => { selected.value = v }

const doDownloadSelected = async () => {
  const names = downloadableSelected.value
  if (!names.length) return
  downloading.value = true
  try {
    await downloadBaseModels(names)
    ElMessage.success(t('已提交 {n} 项下载任务', { n: names.length }))
    rows.value = await listBaseModels()
  } finally {
    downloading.value = false
  }
  ensurePolling()
}

const doDownloadOne = async (row) => {
  singleDl.value = row.modelName
  try {
    await downloadBaseModels([row.modelName])
    ElMessage.success(t('已提交下载任务'))
    rows.value = await listBaseModels()
  } finally {
    singleDl.value = ''
  }
  ensurePolling()
}

const doToggle = async (row, enabled) => {
  try {
    await toggleBaseModelEnabled(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? t('已启用') : t('已下架'))
  } catch (e) {
    // 请求失败由拦截器提示
  }
}

const doDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('确认删除基础模型目录条目 {name}？此操作不会删除服务器上已存在的 .pt 文件。', { name: row.modelName }),
      t('提示'), { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') }
    )
  } catch { return }
  await deleteBaseModel(row.id)
  ElMessage.success(t('已删除'))
  load()
}

/* ---------------- 官网弹窗 ---------------- */
const openOfficial = async () => {
  officialVisible.value = true
  await fetchOfficial(false)
}
const fetchOfficial = async (rerun) => {
  officialLoading.value = true
  try {
    // 后端 POST /sync-official 已把新条目 upsert 到 DB，并返回完整官方列表
    const list = await syncOfficialBaseModels()
    officialRows.value = Array.isArray(list) ? list : (list.items || [])
    if (rerun) ElMessage.success(t('已从官网同步：{n} 项', { n: officialRows.value.length }))
    // 弹窗打开时也刷一下主列表，让新入库条目立即可见
    rows.value = await listBaseModels()
  } finally {
    officialLoading.value = false
  }
}
const onOfficialSel = (v) => { officialSel.value = v }

const doSyncSelected = async (withDownload) => {
  const picks = officialSel.value.map((r) => r.modelName)
  if (!picks.length) {
    ElMessage.warning(t('请先劾选要操作的条目'))
    return
  }
  // 入库已由后端 sync-official 完成；此处按需再触发下载
  if (!withDownload) {
    ElMessage.success(t('已入库 {n} 项，可在主列表劾选后下载', { n: picks.length }))
    officialVisible.value = false
    rows.value = await listBaseModels()
    return
  }
  downloading.value = true
  try {
    await downloadBaseModels(picks)
    ElMessage.success(t('已提交 {n} 项下载任务', { n: picks.length }))
    officialVisible.value = false
    rows.value = await listBaseModels()
  } finally {
    downloading.value = false
  }
  ensurePolling()
}

onMounted(() => { load() })
onBeforeUnmount(() => { stopPolling() })
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.page-header h3 { font-size: 18px; margin-bottom: 4px; }
.sub { color: #909399; font-size: 13px; }
.actions { display: flex; gap: 8px; }
.err { color: #f56c6c; font-size: 12px; display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.hint { color: #606266; font-size: 13px; }
code { background: #f5f7fa; padding: 1px 4px; border-radius: 3px; font-family: Consolas, Monaco, monospace; }
</style>
