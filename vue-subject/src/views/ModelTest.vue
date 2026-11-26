<template>
  <div class="test-page">
    <el-card shadow="never" class="test-card">
      <div class="page-header">
        <div>
          <h3>{{ $t('模型调用测试') }}</h3>
          <span class="sub">{{ $t('选择模型版本，上传测试图片，调用 FastAPI 推理服务并可视化检测结果') }}</span>
        </div>
        <el-button @click="$router.back()">{{ $t('返回') }}</el-button>
      </div>

      <div class="version-picker">
        <span class="picker-label">{{ $t('模型版本：') }}</span>
        <el-select
          v-model="selectedModelId"
          filterable
          :placeholder="$t('搜索选择版本（版本号 / 名称）')"
          style="width:340px"
        >
          <el-option
            v-for="m in models"
            :key="m.id"
            :label="`${m.version}${m.isActive === 1 ? $t('（已上线）') : ''} · ${m.name || $t('未命名')}`"
            :value="m.id"
          >
            <span style="float:left">
              {{ m.version }}
              <span v-if="m.uuid" style="color:#909399;font-size:12px;margin-left:6px" :title="m.uuid">{{ m.uuid.slice(0, 8) }}…</span>
            </span>
            <span style="float:right;color:#909399;font-size:12px">
              {{ formatTime(m.lastTrainedAt || m.createdAt) }}
            </span>
          </el-option>
        </el-select>
        <el-tag v-if="currentModel?.isActive === 1" type="success" size="small" style="margin-left:8px">{{ $t('已上线') }}</el-tag>
      </div>

      <div class="test-body">
        <div class="upload-side">
          <el-upload
            drag
            :auto-upload="false"
            :show-file-list="false"
            accept="image/*"
            :on-change="onFileChange"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">{{ $t('拖拽图片到此处，或') }} <em>{{ $t('点击选择') }}</em></div>
          </el-upload>
          <div v-if="previewUrl" class="preview-box">
            <img :src="previewUrl" class="preview-img" @load="updatePreviewSize" />
            <div v-if="detections" class="result-info">
              <el-tag type="success">{{ $t('检出 {n} 个目标', { n: detections.length }) }}</el-tag>
              <el-tag type="info" style="margin-left:6px">{{ $t('耗时 {ms} ms', { ms: inferenceTimeMs }) }}</el-tag>
            </div>
          </div>
          <el-button
            type="primary"
            size="large"
            :disabled="!file || !selectedModelId"
            :loading="testing"
            style="margin-top:12px"
            @click="doTest"
          >{{ $t('开始推理测试') }}</el-button>
        </div>

        <div class="result-side">
          <div v-if="serverImageSrc" class="view-toggle">
            <el-radio-group v-model="viewMode" size="small">
              <el-radio-button value="canvas">{{ $t('Canvas 标注') }}</el-radio-button>
              <el-radio-button value="server">{{ $t('服务端标注图') }}</el-radio-button>
            </el-radio-group>
          </div>
          <div class="canvas-area">
            <el-image
              v-if="viewMode === 'server' && serverImageSrc"
              :src="serverImageSrc"
              fit="contain"
              style="max-width:100%"
              :preview-src-list="[serverImageSrc]"
            />
            <AnnotationCanvas
              v-else-if="previewUrl"
              ref="canvasRef"
              :image-url="previewUrl"
              :annotations="detections || []"
              :width="viewWidth"
              :height="viewHeight"
              :editable="false"
            />
            <el-empty v-else :description="$t('请先选择测试图片')" />
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import AnnotationCanvas from '@/components/AnnotationCanvas.vue'
import { listModels, testModel } from '@/api/model'
import { t } from '@/i18n'

const route = useRoute()
const router = useRouter()

const models = ref([])
const selectedModelId = ref(Number(route.params.id) || null)

const currentModel = computed(() => models.value.find((m) => m.id === selectedModelId.value))

const file = ref(null)
const previewUrl = ref('')
const detections = ref([])
const inferenceTimeMs = ref(0)
const testing = ref(false)
const canvasRef = ref(null)
const viewWidth = ref(800)
const viewHeight = ref(600)
const annotatedImageUrl = ref('')
const annotatedImageBase64 = ref('')
const viewMode = ref('canvas')

const serverImageSrc = computed(() => annotatedImageUrl.value || annotatedImageBase64.value || '')

const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '-')

const loadModels = async () => {
  models.value = await listModels()
  if (!selectedModelId.value && models.value.length > 0) {
    selectedModelId.value = models.value[0].id
  }
}

watch(selectedModelId, (val) => {
  if (val) {
    const target = `/models/${val}/test`
    if (route.fullPath !== target) router.replace(target)
  }
})

const onFileChange = (uploadFile) => {
  if (uploadFile.raw) {
    file.value = uploadFile.raw
    previewUrl.value = URL.createObjectURL(uploadFile.raw)
    detections.value = []
    inferenceTimeMs.value = 0
    annotatedImageUrl.value = ''
    annotatedImageBase64.value = ''
    viewMode.value = 'canvas'
  }
}

const updatePreviewSize = (e) => {
  const img = e.target
  const maxW = 760
  const scale = Math.min(1, maxW / (img.naturalWidth || maxW))
  viewWidth.value = Math.round((img.naturalWidth || 800) * scale)
  viewHeight.value = Math.round((img.naturalHeight || 600) * scale)
  canvasRef.value?.draw?.()
}

const doTest = async () => {
  if (!selectedModelId.value) {
    ElMessage.warning(t('请先选择模型版本'))
    return
  }
  testing.value = true
  try {
    const resp = await testModel(selectedModelId.value, file.value)
    detections.value = resp.detections || []
    inferenceTimeMs.value = resp.inferenceTimeMs || 0
    annotatedImageUrl.value = resp.annotatedImageUrl || ''
    annotatedImageBase64.value = resp.annotatedImageBase64 || ''
    viewMode.value = serverImageSrc.value ? 'server' : 'canvas'
    ElMessage.success(t('推理完成，检出 {n} 个目标', { n: detections.value.length }))
  } finally {
    testing.value = false
  }
}

onMounted(loadModels)
</script>

<style scoped>
.test-page { display: flex; }
.test-card { flex: 1; }
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h3 { font-size: 18px; }
.sub { color: #909399; font-size: 13px; }
.version-picker { display: flex; align-items: center; margin-top: 12px; }
.picker-label { font-size: 13px; color: #606266; margin-right: 8px; }
.test-body {
  display: flex;
  gap: 20px;
  margin-top: 16px;
}
.upload-side {
  width: 320px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.preview-box { margin-top: 12px; width: 100%; }
.preview-img {
  width: 100%;
  max-height: 260px;
  object-fit: contain;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}
.result-info { margin-top: 8px; }
.result-side { flex: 1; }
.view-toggle { margin-bottom: 8px; }
.canvas-area { width: 100%; overflow: auto; }
</style>
