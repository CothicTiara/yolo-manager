<template>
  <div>
    <el-card shadow="never">
      <div class="page-header">
        <div>
          <h3>{{ $t('训练任务') }}</h3>
          <span class="sub">{{ $t('选择数据集与基础模型，提交 YOLO 训练任务（异步执行）') }}</span>
        </div>
        <el-button type="primary" :loading="creating" @click="openCreate">
          <el-icon style="margin-right:4px"><VideoPlay /></el-icon>{{ $t('创建训练任务') }}
        </el-button>
      </div>

      <el-table :data="tasks" v-loading="loading" style="margin-top:16px">
        <!-- 伪 ID：从 1 开始的序号，真实 id 隐藏 -->
        <el-table-column type="index" label="ID" width="70" />
        <el-table-column prop="datasetName" :label="$t('数据集')" min-width="120" />
        <el-table-column prop="modelBase" :label="$t('基础模型')" width="100" />
        <el-table-column :label="$t('训练来源')" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.baseModelType === 'TRAINED'" size="small" type="warning">
              {{ $t('续训') }} {{ row.baseVersionName || $t('源版本') }}
            </el-tag>
            <el-tag v-else size="small" type="info">{{ $t('基础训练') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('配置')" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" type="info">epochs {{ row.epochs }}</el-tag>
            <el-tag size="small" type="info" style="margin-left:4px">batch {{ row.batchSize }}</el-tag>
            <el-tag size="small" type="info" style="margin-left:4px">imgsz {{ row.imgsz }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('状态')" width="210">
          <template #default="{ row }">
            <div class="status-cell">
              <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-progress
                v-if="showTrainProgress(row.status)"
                :percentage="trainPercent(row)"
                :status="trainBarStatus(row.status)"
                :indeterminate="row.status === 'QUEUED' || row.status === 'CREATED' || (row.status === 'RUNNING' && !(row.progress > 0))"
                :striped="true"
                :duration="3"
                :stroke-width="10"
                :format="() => trainFormatText(row)"
                class="mini-progress"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" :label="$t('错误信息')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="$t('操作')" width="220">
          <template #default="{ row }">
            <el-button size="small" type="primary" text :loading="metricsLoading === row.id" @click="showMetrics(row)">
              {{ $t('指标') }}
            </el-button>
            <el-button
              v-if="row.status === 'QUEUED' || row.status === 'RUNNING' || row.status === 'CREATED'"
              size="small"
              type="warning"
              text
              :loading="cancellingId === row.id"
              @click="doCancel(row)"
            >
              {{ $t('中断') }}
            </el-button>
            <el-button
              v-else
              size="small"
              type="danger"
              text
              :loading="deletingId === row.id"
              @click="doDelete(row)"
            >
              {{ $t('删除') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建训练任务 -->
    <el-dialog v-model="createVisible" :title="$t('创建训练任务')" width="540px">
      <el-form :model="createForm" label-width="110px">
        <el-form-item :label="$t('数据集')">
          <el-select v-model="createForm.datasetId" :placeholder="$t('选择数据集')" filterable style="width:100%">
            <el-option
              v-for="d in datasets"
              :key="d.id"
              :label="$t('{name}（{total} 张，已标注 {annotated}）', { name: d.name, total: d.imageCount ?? 0, annotated: d.annotatedCount ?? 0 })"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="selectedUnannotated > 0"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:14px"
          :title="$t('该数据集还有 {n} 张图片未标注（已标注 {a}/{b}），建议补全标注并导出后再训练，避免标注不全影响模型效果', { n: selectedUnannotated, a: selectedDataset.annotatedCount ?? 0, b: selectedDataset.imageCount ?? 0 })"
        />
        <el-form-item :label="$t('模型类型')">
          <el-radio-group v-model="createForm.baseModelType" @change="onTypeChange">
            <el-radio-button value="BASE">{{ $t('基础模型') }}</el-radio-button>
            <el-radio-button value="TRAINED">{{ $t('已训练模型') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="createForm.baseModelType === 'TRAINED' ? $t('源模型版本') : $t('基础模型')">
          <el-select
            v-model="modelSelectValue"
            filterable
            clearable
            :placeholder="createForm.baseModelType === 'TRAINED' ? $t('搜索选择已训练版本') : $t('搜索选择基础模型')"
            style="width:100%"
            @change="onModelSelected"
          >
            <el-option
              v-for="opt in filteredModelOptions"
              :key="opt.type + '-' + opt.value"
              :label="opt.label"
              :value="opt.type + '-' + opt.value"
            >
              <span style="float:left">{{ opt.label }}</span>
              <span style="float:right;color:#909399;font-size:12px">
                {{ opt.type === 'TRAINED' ? formatTime(opt.lastTrainedAt) : $t('基础') }}
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.baseModelType === 'TRAINED'" :label="$t('版本升级')">
          <el-radio-group v-model="createForm.versionBump">
            <el-radio-button value="MINOR">{{ $t('小版本') }} (+0.1)</el-radio-button>
            <el-radio-button value="MAJOR">{{ $t('大版本') }} (+1.0)</el-radio-button>
          </el-radio-group>
          <div v-if="previewVersion" class="version-preview">
            {{ $t('预期生成版本：') }}<el-tag type="success" size="small">{{ previewVersion }}</el-tag>
          </div>
        </el-form-item>
        <el-form-item v-if="createForm.baseModelType === 'BASE'" :label="$t('预期版本')">
          <div class="version-preview">
            {{ $t('初次训练开启新系列，版本号从') }} <el-tag type="success" size="small">v1.0.0</el-tag> {{ $t('开始，后续续训在本系列内升级') }}
          </div>
        </el-form-item>
        <el-form-item :label="$t('训练轮数')">
          <el-input-number v-model="createForm.epochs" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item :label="$t('批次大小')">
          <el-input-number v-model="createForm.batchSize" :min="1" :max="256" />
        </el-form-item>
        <el-form-item :label="$t('输入尺寸')">
          <el-input-number v-model="createForm.imgsz" :min="32" :max="4096" :step="32" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ $t('取消') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">{{ $t('提交训练') }}</el-button>
      </template>
    </el-dialog>

    <!-- 训练指标 -->
    <el-dialog v-model="metricsVisible" :title="`${$t('训练指标')} - ${metricsTask?.datasetName || ''}`" width="720px">
      <div ref="chartRef" style="width:100%;height:360px"></div>
      <el-empty v-if="metricsEmpty" :description="$t('暂无指标数据（训练尚未产生指标或未运行）')" />
    </el-dialog>
  </div>
</template>
<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { listDatasets } from '@/api/dataset'
import {
  createTrainTask,
  listTrainTasks,
  getTrainMetrics,
  cancelTrainTask,
  deleteTrainTask,
  listBaseModels
} from '@/api/train'
import { t } from '@/i18n'

const tasks = ref([])
const datasets = ref([])
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const metricsVisible = ref(false)
const metricsLoading = ref(null)
const cancellingId = ref(null)
const deletingId = ref(null)

const doCancel = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('确认中断训练任务「{name}」？', { name: row.datasetName }),
      t('提示'),
      { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') }
    )
  } catch {
    return
  }
  cancellingId.value = row.id
  try {
    await cancelTrainTask(row.id)
    ElMessage.success(t('已发送取消信号'))
    load()
  } finally {
    cancellingId.value = null
  }
}

const doDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('确认删除训练任务「{name}」？该操作不可恢复。', { name: row.datasetName }),
      t('提示'),
      { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') }
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteTrainTask(row.id)
    ElMessage.success(t('删除成功'))
    load()
  } finally {
    deletingId.value = null
  }
}
const metricsEmpty = ref(false)
const metricsTask = ref(null)
const chartRef = ref(null)
let chart = null

const modelOptions = ref([])
const modelSelectValue = ref('')
const createForm = reactive({
  datasetId: null,
  modelBase: 'yolov8n',
  baseModelType: 'BASE',
  baseVersionId: null,
  versionBump: 'MINOR',
  epochs: 100,
  batchSize: 16,
  imgsz: 640
})

const filteredModelOptions = computed(() =>
  modelOptions.value.filter((o) => o.type === createForm.baseModelType)
)

/* 当前选中数据集的标注缺口（未标注图片数），用于创建对话框警告 */
const selectedDataset = computed(() =>
  datasets.value.find((d) => d.id === createForm.datasetId) || null
)
const selectedUnannotated = computed(() => {
  const d = selectedDataset.value
  if (!d) return 0
  return Math.max(0, (d.imageCount ?? 0) - (d.annotatedCount ?? 0))
})

const previewVersion = computed(() => {
  if (createForm.baseModelType !== 'TRAINED') return ''
  const opt = modelOptions.value.find((o) => o.type === 'TRAINED' && String(o.versionId) === String(createForm.baseVersionId))
  if (!opt || !opt.version) return ''
  return bumpVersion(opt.version, createForm.versionBump)
})

function bumpVersion(base, bump) {
  if (!base) return ''
  const s = base.startsWith('v') ? base.substring(1) : base
  const parts = s.split('.').map((n) => parseInt(n || '0', 10) || 0)
  let major = parts[0] || 1, minor = parts[1] || 0, patch = parts[2] || 0
  if (bump === 'MAJOR') { major += 1; minor = 0; patch = 0 }
  else { minor += 1; patch = 0 }
  return `v${major}.${minor}.${patch}`
}

function onTypeChange(val) {
  modelSelectValue.value = ''
  createForm.baseVersionId = null
  if (val === 'BASE') {
    createForm.modelBase = 'yolov8n'
  } else {
    createForm.modelBase = ''
  }
}

function onModelSelected(val) {
  if (!val) return
  const opt = modelOptions.value.find((o) => `${o.type}-${o.value}` === val)
  if (!opt) return
  if (opt.type === 'BASE') {
    createForm.modelBase = opt.modelBase
    createForm.baseVersionId = null
  } else {
    createForm.baseVersionId = Number(opt.value)
    createForm.modelBase = opt.modelBase || ''
  }
}

const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '')

const statusType = (s) => {
  const map = { CREATED: 'info', QUEUED: 'warning', RUNNING: 'primary', SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info' }
  return map[s] || 'info'
}

/* ---------- 训练进度与自动轮询 ---------- */
const ACTIVE_STATUSES = ['CREATED', 'QUEUED', 'RUNNING']
const STATUS_TEXT = { CREATED: '已创建', QUEUED: '排队中', RUNNING: '训练中', SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const statusText = (s) => t(STATUS_TEXT[s] || s)
const showTrainProgress = (s) => ACTIVE_STATUSES.includes(s) || s === 'SUCCESS'
const trainPercent = (row) => {
  if (row.status !== 'RUNNING') return 100 // 排队/已创建：满宽动画条；成功：100%
  return Math.min(99, Math.round(((row.progress || 0) / (row.epochs || 1)) * 100))
}
const trainBarStatus = (s) => (s === 'SUCCESS' ? 'success' : undefined)
const trainFormatText = (row) => {
  if (row.status === 'QUEUED' || row.status === 'CREATED') return t('排队中')
  if (row.status === 'SUCCESS') return t('完成')
  // RUNNING 但进度为 0：worker 已在准备数据，首个 epoch 尚未完成
  if (row.status === 'RUNNING' && !(row.progress > 0)) return t('准备数据中')
  return t('{cur}/{total} 轮', { cur: row.progress || 0, total: row.epochs || 0 })
}
let refreshTimer = null
const stopPolling = () => { if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null } }
// 有任务处于活跃状态时每 3s 刷新列表，全部终态则自动停止
const ensurePolling = () => {
  const hasActive = tasks.value.some((t) => ACTIVE_STATUSES.includes(t.status))
  if (hasActive && !refreshTimer) {
    refreshTimer = setInterval(async () => {
      try {
        tasks.value = await listTrainTasks()
      } catch (e) { /* 忽略单次失败 */ }
      if (!tasks.value.some((t) => ACTIVE_STATUSES.includes(t.status))) stopPolling()
    }, 3000)
  } else if (!hasActive) {
    stopPolling()
  }
}

const load = async () => {
  loading.value = true
  try {
    tasks.value = await listTrainTasks()
  } finally {
    loading.value = false
  }
  ensurePolling()
}

const openCreate = async () => {
  datasets.value = await listDatasets()
  modelOptions.value = await listBaseModels()
  // 默认选中首个 BASE 项
  createForm.baseModelType = 'BASE'
  createForm.versionBump = 'MINOR'
  createForm.baseVersionId = null
  const first = modelOptions.value.find((o) => o.type === 'BASE')
  modelSelectValue.value = first ? `BASE-${first.value}` : ''
  createForm.modelBase = first ? first.modelBase : 'yolov8n'
  createVisible.value = true
}

const submitCreate = async () => {
  if (!createForm.datasetId) {
    ElMessage.warning(t('请选择数据集'))
    return
  }
  if (createForm.baseModelType === 'BASE' && !createForm.modelBase) {
    ElMessage.warning(t('请选择基础模型'))
    return
  }
  if (createForm.baseModelType === 'TRAINED' && !createForm.baseVersionId) {
    ElMessage.warning(t('请选择续训的源模型版本'))
    return
  }
  creating.value = true
  try {
    await createTrainTask({
      datasetId: createForm.datasetId,
      modelBase: createForm.modelBase || undefined,
      baseModelType: createForm.baseModelType,
      baseVersionId: createForm.baseModelType === 'TRAINED' ? createForm.baseVersionId : null,
      versionBump: createForm.baseModelType === 'TRAINED' ? createForm.versionBump : 'MINOR',
      epochs: createForm.epochs,
      batchSize: createForm.batchSize,
      imgsz: createForm.imgsz
    })
    ElMessage.success(t('训练任务已创建'))
    createVisible.value = false
    load()
  } finally {
    creating.value = false
  }
}

const showMetrics = async (row) => {
  metricsTask.value = row
  metricsVisible.value = true
  metricsLoading.value = row.id
  metricsEmpty.value = false
  try {
    const points = await getTrainMetrics(row.id)
    if (points.length === 0) {
      metricsEmpty.value = true
      return
    }
    await nextTick()
    renderChart(points)
  } finally {
    metricsLoading.value = null
  }
}

const renderChart = (points) => {
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const epochs = points.map((p) => p.epoch)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['boxLoss', 'clsLoss', 'map50', 'map50-95'] },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: epochs, name: 'epoch' },
    yAxis: { type: 'value', name: t('指标值') },
    series: [
      { name: 'boxLoss', type: 'line', smooth: true, data: points.map((p) => p.boxLoss) },
      { name: 'clsLoss', type: 'line', smooth: true, data: points.map((p) => p.clsLoss) },
      { name: 'map50', type: 'line', smooth: true, data: points.map((p) => p.map50) },
      { name: 'map50-95', type: 'line', smooth: true, data: points.map((p) => p.map5095) }
    ]
  })
}

onMounted(async () => {
  await load()
  // 从模型列表「继续训练」跳转而来：预填 TRAINED + 源版本并打开创建对话框
  const baseVersionId = Number(route.query.baseVersionId)
  if (baseVersionId) {
    await openCreate()
    const opt = modelOptions.value.find((o) => o.type === 'TRAINED' && Number(o.value) === baseVersionId)
    if (opt) {
      createForm.baseModelType = 'TRAINED'
      createForm.baseVersionId = baseVersionId
      createForm.modelBase = opt.modelBase || ''
      modelSelectValue.value = `TRAINED-${opt.value}`
    } else {
      ElMessage.warning(t('源模型版本不存在或已被删除，请手动选择'))
    }
    router.replace({ path: route.path })
  }
})
onBeforeUnmount(() => {
  stopPolling()
  chart?.dispose()
})
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h3 { font-size: 18px; }
.sub { color: #909399; font-size: 13px; }
.status-cell { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.mini-progress { width: 150px; }
.version-preview { margin-top: 6px; color: #606266; font-size: 12px; }
</style>