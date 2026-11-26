<template>
  <div class="annotate-page">
    <el-alert
      v-if="isBusy"
      type="warning"
      :closable="false"
      show-icon
      class="busy-alert"
      :title="$t('该数据集正在进行{label}，完成前禁止新增/编辑/删除标注与标签，也请避免上传或删除图片。页面会自动刷新，请稍候…', { label: busyLabel })"
    />
    <el-card shadow="never" class="annotate-layout">
      <!-- 左侧：图片列表 -->
      <div class="image-side">
        <div class="side-title">{{ $t('图片列表（{n}）', { n: images.length }) }}</div>
        <div class="image-list" v-loading="imagesLoading">
          <div
            v-for="(img, idx) in images"
            :key="img.id"
            class="thumb"
            :class="{ active: img.id === currentImage?.id }"
            @click="selectImage(img)"
          >
            <el-image :src="img.url" fit="cover" class="thumb-img" />
            <span class="thumb-name">{{ idx + 1 }}</span>
            <span v-if="dirtySet.has(img.id)" class="dirty-badge">{{ $t('未保存') }}</span>
            <span v-else class="ann-badge" :class="(img.annotationCount ?? 0) > 0 ? 'ok' : 'none'">
              {{ (img.annotationCount ?? 0) > 0 ? $t('已标注') : $t('未标注') }}
            </span>
          </div>
          <el-empty v-if="!imagesLoading && images.length === 0" :description="$t('暂无图片')" :image-size="60" />
        </div>
      </div>

      <!-- 中间：画布 -->
      <div class="canvas-side">
        <div class="canvas-toolbar">
          <span class="tool-tip">{{ $t('拖拽空白区绘制框 · 选中后可拖动移动 / 拖拽角边缩放 · Delete 删除 · Esc 取消选中') }}</span>
          <div class="tool-right">
            <el-tag v-if="activeLabel" :color="activeLabel.color" effect="dark" class="active-label">
              {{ $t('当前标签：') }}{{ activeLabel.name }}
            </el-tag>
            <el-tag v-else type="warning" class="active-label">{{ $t('请先选择标签') }}</el-tag>
            <el-button
              type="success"
              :disabled="!currentImage || !dirtySet.has(currentImage.id) || isBusy"
              :loading="saving"
              @click="doSave"
            >
              {{ $t('保存标注') }}
            </el-button>
            <el-button
              :disabled="!currentImage || !dirtySet.has(currentImage.id) || isBusy"
              @click="doDiscard"
            >
              {{ $t('放弃修改') }}
            </el-button>
          </div>
        </div>
        <AnnotationCanvas
          v-if="currentImage"
          ref="canvasRef"
          :image-url="currentImage.url"
          :annotations="renderAnnotations"
          :width="canvasWidth"
          :height="canvasHeight"
          :active-label-color="activeLabel?.color || '#409EFF'"
          @create="onCreate"
          @update="onUpdate"
          @remove="onRemove"
          @select-index="selectedIdx = $event"
        />
        <el-empty v-else :description="$t('请选择一张图片开始标注')" />
      </div>

      <!-- 右侧：标签管理 + 标注列表 -->
      <div class="label-side">
        <div class="side-title">
          {{ $t('标签管理') }}
          <el-button type="primary" size="small" circle :disabled="isBusy" @click="showLabelDialog = true">＋</el-button>
        </div>
        <div class="label-list">
          <div
            v-for="label in labels"
            :key="label.id"
            class="label-item"
            :class="{ active: activeLabel?.id === label.id }"
            @click="activeLabel = label"
          >
            <span class="label-dot" :style="{ background: label.color }"></span>
            <span class="label-name">{{ label.name }}</span>
            <span class="label-code">{{ label.code }}</span>
            <el-button type="primary" size="small" text :disabled="isBusy" @click.stop="openRename(label)">{{ $t('改') }}</el-button>
            <el-button type="danger" size="small" text :disabled="isBusy" @click="removeLabel(label)">{{ $t('删') }}</el-button>
          </div>
          <el-empty v-if="labels.length === 0" :description="$t('暂无标签')" :image-size="50" />
        </div>

        <el-divider />

        <div class="side-title">{{ $t('当前图片标注（{n}）', { n: renderAnnotations.length }) }}</div>
        <div class="ann-list">
          <div
            v-for="(ann, idx) in renderAnnotations"
            :key="ann.id || 'tmp-' + idx"
            class="ann-item"
            :class="{ selected: selectedIdx === idx }"
            @click="handleAnnItemClick(idx)"
          >
            <span class="label-dot" :style="{ background: ann.labelColor }"></span>
            <span class="ann-label">{{ ann.labelName }}</span>
            <span class="ann-coord">{{ formatCoord(ann) }}</span>
            <el-button type="danger" size="small" text :disabled="isBusy" @click.stop="removeAnnotation(idx)">✕</el-button>
          </div>
          <el-empty v-if="renderAnnotations.length === 0" :description="$t('暂无标注')" :image-size="50" />
        </div>
      </div>
    </el-card>

    <!-- 新建标签 -->
    <el-dialog v-model="showLabelDialog" :title="$t('新建标签')" width="400px">
      <el-form :model="labelForm" label-width="80px">
        <el-form-item :label="$t('名称')">
          <el-input v-model="labelForm.name" :placeholder="$t('如：person / 汽车')" />
        </el-form-item>
        <el-form-item :label="$t('颜色')">
          <el-color-picker v-model="labelForm.color" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showLabelDialog = false">{{ $t('取消') }}</el-button>
        <el-button type="primary" :loading="savingLabel" @click="submitLabel">{{ $t('保存') }}</el-button>
      </template>
    </el-dialog>

    <!-- 重命名标签 -->
    <el-dialog v-model="showRenameDialog" :title="$t('重命名标签')" width="400px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:14px"
        :title="$t('改名后该标签下的全部已有标注统一使用新名称，类别序号与颜色不变；已导出的标注需重新导出才会生效。')"
      />
      <el-form label-width="80px">
        <el-form-item :label="$t('新名称')">
          <el-input v-model="renameForm.name" :placeholder="$t('输入新的标签名称')" @keyup.enter="submitRename" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRenameDialog = false">{{ $t('取消') }}</el-button>
        <el-button type="primary" :loading="renaming" @click="submitRename">{{ $t('保存') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AnnotationCanvas from '@/components/AnnotationCanvas.vue'
import {
  listImages,
  listLabels,
  saveLabel,
  renameLabel,
  deleteLabel,
  getAnnotations,
  syncAnnotations,
  getDataset
} from '@/api/dataset'
import { t } from '@/i18n'

const route = useRoute()
const datasetId = Number(route.params.id)

const images = ref([])
const currentImage = ref(null)
const imagesLoading = ref(false)
// 本地 draft 数据：imageId -> [{id?, labelId, labelName, labelColor, xCenter, yCenter, width, height}]
const draft = ref({})
// 原始快照用于"放弃修改"：imageId -> 上次加载/保存时的列表
const snapshot = ref({})
// 已修改未保存的 imageId 集合
const dirtySet = ref(new Set())

const labels = ref([])
const activeLabel = ref(null)
const canvasRef = ref(null)
const selectedIdx = ref(-1)
const saving = ref(false)

const showLabelDialog = ref(false)
const savingLabel = ref(false)
const labelForm = reactive({ name: '', color: '#409EFF' })

const showRenameDialog = ref(false)
const renaming = ref(false)
const renameTarget = ref(null)
const renameForm = reactive({ name: '' })

const canvasWidth = 800
const canvasHeight = 600

/* ---------- 后台任务忙碌状态（导入/导出/上传） ---------- */
const BUSY_LABELS = { IMPORT: '导入', EXPORT: '导出', UPLOAD: '上传' }
const busyKind = ref(null)
const isBusy = computed(() => !!busyKind.value)
const busyLabel = computed(() => t(BUSY_LABELS[busyKind.value] || '处理'))
let busyTimer = null
const stopBusyPolling = () => { if (busyTimer) { clearInterval(busyTimer); busyTimer = null } }
const applyBusyKind = (kind) => {
  busyKind.value = kind || null
  if (busyKind.value && !busyTimer) {
    busyTimer = setInterval(refreshBusy, 3000)
  } else if (!busyKind.value) {
    stopBusyPolling()
  }
}
const refreshBusy = async () => {
  try {
    const d = await getDataset(datasetId)
    const wasBusy = !!busyKind.value
    applyBusyKind(d?.jobKind)
    // 刚从忙碌转为空闲：后台任务可能新增/变更了数据，刷新标签与图片
    if (wasBusy && !busyKind.value) {
      await Promise.all([loadLabels(), loadImages()])
      ElMessage.success(t('后台任务已完成，已刷新最新数据'))
    }
  } catch (e) { /* 单次失败忽略 */ }
}

const renderAnnotations = computed(() => {
  if (!currentImage.value) return []
  const list = draft.value[currentImage.value.id] || []
  return list.map((a) => {
    const label = labels.value.find((l) => l.id === a.labelId)
    return {
      ...a,
      labelName: a.labelName || label?.name || '',
      labelColor: a.labelColor || label?.color || '#409EFF'
    }
  })
})

const formatCoord = (a) =>
  `${Number(a.xCenter).toFixed(2)},${Number(a.yCenter).toFixed(2)} ${Number(a.width).toFixed(2)}×${Number(a.height).toFixed(2)}`

/* ---------- 加载 ---------- */
const loadImages = async () => {
  imagesLoading.value = true
  try {
    images.value = await listImages(datasetId)
    if (images.value.length > 0 && !currentImage.value) {
      await selectImage(images.value[0])
    }
  } finally {
    imagesLoading.value = false
  }
}

const loadLabels = async () => {
  labels.value = await listLabels(datasetId)
}

const ensureLoaded = async (imageId) => {
  if (draft.value[imageId]) return
  const list = await getAnnotations(imageId)
  const clone = JSON.parse(JSON.stringify(list))
  draft.value[imageId] = clone
  snapshot.value[imageId] = JSON.parse(JSON.stringify(list))
}

const selectImage = async (img) => {
  if (currentImage.value?.id === img.id) return
  // 未保存修改提示
  if (currentImage.value && dirtySet.value.has(currentImage.value.id)) {
    try {
      await ElMessageBox.confirm(
        t('当前图片有未保存的标注修改，是否放弃？'),
        t('提示'),
        { confirmButtonText: t('放弃并切换'), cancelButtonText: t('留在本页'), type: 'warning' }
      )
    } catch {
      return
    }
    // 放弃：从 snapshot 恢复
    restoreFromSnapshot(currentImage.value.id)
  }
  currentImage.value = img
  selectedIdx.value = -1
  await ensureLoaded(img.id)
}

function restoreFromSnapshot(imageId) {
  draft.value[imageId] = JSON.parse(JSON.stringify(snapshot.value[imageId] || []))
  dirtySet.value.delete(imageId)
  dirtySet.value = new Set(dirtySet.value)
}

function markDirty(imageId) {
  if (!dirtySet.value.has(imageId)) {
    dirtySet.value.add(imageId)
    dirtySet.value = new Set(dirtySet.value)
  }
}

/* ---------- Draft 编辑：全部本地操作 ---------- */
const onCreate = (ann) => {
  if (isBusy.value) {
    ElMessage.warning(t('该数据集正在进行{label}，完成前禁止编辑标注', { label: busyLabel.value }))
    return
  }
  if (!activeLabel.value) {
    ElMessage.warning(t('请先在右侧选择标签'))
    return
  }
  const item = {
    id: null, // 临时项，保存后后端会返回真实 id
    labelId: activeLabel.value.id,
    labelName: activeLabel.value.name,
    labelColor: activeLabel.value.color,
    xCenter: ann.xCenter,
    yCenter: ann.yCenter,
    width: ann.width,
    height: ann.height
  }
  const list = draft.value[currentImage.value.id] || []
  list.push(item)
  draft.value[currentImage.value.id] = [...list]
  selectedIdx.value = list.length - 1
  markDirty(currentImage.value.id)
}

const onUpdate = (idx, ann) => {
  const list = draft.value[currentImage.value.id] || []
  if (idx < 0 || idx >= list.length) return
  list[idx] = {
    ...list[idx],
    xCenter: ann.xCenter,
    yCenter: ann.yCenter,
    width: ann.width,
    height: ann.height
  }
  draft.value[currentImage.value.id] = [...list]
  markDirty(currentImage.value.id)
}

const onRemove = (ann, idx) => {
  removeAnnotation(idx)
}

const removeAnnotation = async (idx) => {
  const list = draft.value[currentImage.value.id] || []
  const target = list[idx]
  if (!target) return
  try {
    await ElMessageBox.confirm(t('删除该标注？保存后生效。'), t('提示'), { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') })
  } catch {
    return
  }
  list.splice(idx, 1)
  draft.value[currentImage.value.id] = [...list]
  if (selectedIdx.value === idx) selectedIdx.value = -1
  else if (selectedIdx.value > idx) selectedIdx.value -= 1
  markDirty(currentImage.value.id)
}

const handleAnnItemClick = (idx) => {
  selectedIdx.value = idx
  canvasRef.value?.setSelected(idx)
  const ann = renderAnnotations.value[idx]
  const label = labels.value.find((l) => l.id === ann?.labelId)
  if (label) activeLabel.value = label
}

/* ---------- 保存 / 放弃 ---------- */
const doSave = async () => {
  if (!currentImage.value) return
  const imageId = currentImage.value.id
  const list = draft.value[imageId] || []
  if (list.some((a) => !a.labelId)) {
    ElMessage.error(t('存在未关联标签的标注，请先调整'))
    return
  }
  saving.value = true
  try {
    const items = list.map((a) => ({
      id: a.id ?? null,
      labelId: a.labelId,
      xCenter: Number(a.xCenter),
      yCenter: Number(a.yCenter),
      width: Number(a.width),
      height: Number(a.height)
    }))
    const saved = await syncAnnotations(imageId, items)
    draft.value[imageId] = JSON.parse(JSON.stringify(saved))
    snapshot.value[imageId] = JSON.parse(JSON.stringify(saved))
    dirtySet.value.delete(imageId)
    dirtySet.value = new Set(dirtySet.value)
    selectedIdx.value = -1
    // 同步左侧列表角标的标注数
    const img = images.value.find((i) => i.id === imageId)
    if (img) img.annotationCount = saved.length
    ElMessage.success(t('保存成功，共 {n} 个标注', { n: saved.length }))
  } finally {
    saving.value = false
  }
}

const doDiscard = async () => {
  if (!currentImage.value) return
  try {
    await ElMessageBox.confirm(t('放弃当前图片未保存的标注修改？'), t('提示'), { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') })
  } catch {
    return
  }
  restoreFromSnapshot(currentImage.value.id)
  selectedIdx.value = -1
  ElMessage.success(t('已恢复'))
}

/* ---------- 标签 ---------- */
const submitLabel = async () => {
  savingLabel.value = true
  try {
    await saveLabel(datasetId, { name: labelForm.name, color: labelForm.color })
    ElMessage.success(t('标签已创建'))
    showLabelDialog.value = false
    labelForm.name = ''
    labelForm.color = '#409EFF'
    await loadLabels()
  } finally {
    savingLabel.value = false
  }
}

const removeLabel = async (label) => {
  try {
    await ElMessageBox.confirm(t('确认删除标签「{name}」？其标注将一并删除', { name: label.name }), t('提示'), { type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消') })
  } catch {
    return
  }
  await deleteLabel(datasetId, label.id)
  ElMessage.success(t('已删除'))
  await loadLabels()
}

/* 重命名标签：后端改 label 表，全部标注按 labelId 自动生效；同步本地 draft 缓存的名称 */
const openRename = (label) => {
  renameTarget.value = label
  renameForm.name = label.name
  showRenameDialog.value = true
}
const submitRename = async () => {
  const name = renameForm.name.trim()
  if (!name) {
    ElMessage.warning(t('请输入新的标签名称'))
    return
  }
  renaming.value = true
  try {
    await renameLabel(datasetId, renameTarget.value.id, name)
    ElMessage.success(t('标签已重命名，全部标注已统一使用新名称'))
    showRenameDialog.value = false
    await loadLabels()
    // 刷新本地 draft/snapshot 中缓存的旧名称，避免标注列表仍显示旧名
    for (const map of [draft.value, snapshot.value]) {
      for (const imageId of Object.keys(map)) {
        map[imageId] = (map[imageId] || []).map((a) =>
          a.labelId === renameTarget.value.id ? { ...a, labelName: name } : a
        )
      }
    }
  } finally {
    renaming.value = false
  }
}

watch(activeLabel, () => {
  canvasRef.value?.draw()
})

onMounted(() => {
  loadLabels()
  loadImages()
  refreshBusy()
})

onBeforeUnmount(stopBusyPolling)
</script>

<style scoped>
.annotate-page {
  height: calc(100vh - 110px);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.busy-alert {
  flex: none;
}
.annotate-layout {
  flex: 1;
  min-height: 0;
}
.annotate-layout :deep(.el-card__body) {
  display: flex;
  height: 100%;
  padding: 12px;
  gap: 12px;
}
.image-side {
  width: 180px;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  padding-right: 10px;
}
.canvas-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
  overflow: auto;
}
.label-side {
  width: 260px;
  border-left: 1px solid #e4e7ed;
  padding-left: 12px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.side-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.image-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  overflow-y: auto;
}
.thumb {
  border: 2px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
}
.thumb.active {
  border-color: #409eff;
}
.thumb-img {
  width: 100%;
  height: 80px;
  border-radius: 4px;
  display: block;
}
.thumb-name {
  position: absolute;
  left: 4px;
  bottom: 4px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  padding: 1px 5px;
  border-radius: 3px;
}
.dirty-badge {
  position: absolute;
  right: 4px;
  top: 4px;
  font-size: 10px;
  color: #fff;
  background: #e6a23c;
  padding: 1px 5px;
  border-radius: 3px;
}
.ann-badge {
  position: absolute;
  right: 4px;
  top: 4px;
  font-size: 10px;
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
}
.ann-badge.ok {
  background: #67c23a;
}
.ann-badge.none {
  background: #909399;
}
.canvas-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.tool-tip {
  color: #909399;
  font-size: 12px;
  flex: 1;
}
.tool-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.label-list,
.ann-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.label-item,
.ann-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid transparent;
  font-size: 12px;
}
.ann-item.selected {
  background: #ecf5ff;
  border-color: #409eff;
}
.label-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}
.label-dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex-shrink: 0;
}
.label-name,
.ann-label {
  flex: 1;
  font-size: 13px;
}
.label-code {
  color: #909399;
  font-size: 12px;
}
.ann-coord {
  color: #909399;
  font-size: 11px;
  font-family: monospace;
}
</style>
