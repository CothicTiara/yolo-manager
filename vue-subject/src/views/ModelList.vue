<template>
  <div>
    <el-card shadow="never">
      <div class="page-header">
        <div>
          <h3>{{ $t('模型版本') }}</h3>
          <span class="sub">{{ $t('训练完成后自动注册模型版本，也可直接上传训练好的 .pt 权重，支持激活上线与调用测试') }}</span>
        </div>
        <div>
          <el-button type="primary" @click="openUpload">
            <el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('上传模型') }}
          </el-button>
          <el-button type="primary" plain @click="load">
            <el-icon style="margin-right:4px"><Refresh /></el-icon>{{ $t('刷新') }}
          </el-button>
        </div>
      </div>

      <el-table
        :data="treeModels"
        v-loading="loading"
        style="margin-top:16px"
        row-key="id"
        :tree-props="{ children: 'children' }"
      >
        <!-- 伪 ID：仅系列根行展示从 1 开始的序号，子版本留空；真实 id 隐藏（内部操作仍用 row.id） -->
        <el-table-column :label="$t('ID')" width="90">
          <template #default="{ row }">{{ rootIndexOf(row) }}</template>
        </el-table-column>
        <el-table-column prop="version" :label="$t('版本号')" min-width="120">
          <template #default="{ row }">
            <el-tag size="large" effect="plain">{{ row.version }}</el-tag>
            <el-tag v-if="row.isActive === 1" type="success" size="small" style="margin-left:6px">{{ $t('已上线') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('模型名称')" min-width="180">
          <template #default="{ row }">
            <span class="model-name" :title="row.name">{{ row.name || '-' }}</span>
            <el-button link type="primary" size="small" @click="rename(row)">{{ $t('编辑') }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="baseModel" :label="$t('基底模型')" width="140" show-overflow-tooltip />
        <el-table-column :label="$t('使用训练集')" min-width="200">
          <template #default="{ row }">
            <template v-if="row.datasets && row.datasets.length">
              <el-tag
                v-for="(name, i) in row.datasets"
                :key="i"
                size="small"
                type="info"
                style="margin:2px 4px 2px 0"
              >{{ name }}</el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('UUID（对外接口标识）')" min-width="250">
          <template #default="{ row }">
            <template v-if="row.uuid">
              <span class="uuid-text" :title="row.uuid">{{ row.uuid }}</span>
              <el-button link type="primary" size="small" @click="copyUuid(row.uuid)">{{ $t('复制') }}</el-button>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('创建时间')" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('最后训练完成时间')" width="200">
          <template #default="{ row }">{{ formatTime(row.lastTrainedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('操作')" width="480" class-name="op-col">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="goTest(row)">{{ $t('调用测试') }}</el-button>
            <el-button size="small" plain @click="download(row)">{{ $t('下载') }}</el-button>
            <el-button type="warning" size="small" plain @click="goContinueTrain(row)">{{ $t('继续训练') }}</el-button>
            <el-button
              v-if="row.isActive !== 1"
              type="success"
              size="small"
              :loading="activatingId === row.id"
              @click="activate(row)"
            >{{ $t('激活上线') }}</el-button>
            <el-button
              type="danger"
              size="small"
              plain
              :loading="deletingId === row.id"
              @click="doDelete(row)"
            >{{ $t('删除') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && models.length === 0" :description="$t('暂无模型版本，请先完成训练或上传模型')" />
    </el-card>

    <!-- 上传模型对话框 -->
    <el-dialog v-model="showUpload" :title="$t('上传模型')" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:14px"
        :title="$t('上传训练好的 YOLO 权重（.pt）：文件名与库内模型同名时自动并入该系列并升级版本号，新模型从 v1.0.0 开始；UUID 自动生成；上传后默认未上线，可激活上线、调用测试或作为继续训练的源模型。')"
      />
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".pt"
        :file-list="uploadFileList"
        :on-change="onUploadFileChange"
        :on-remove="onUploadFileRemove"
        :on-exceed="onUploadExceed"
      >
        <el-icon style="font-size:40px;color:#c0c4cc"><Upload /></el-icon>
        <div style="margin-top:8px">{{ $t('将 .pt 文件拖到此处，或') }}<em>{{ $t('点击选择') }}</em></div>
      </el-upload>
      <template #footer>
        <el-button @click="showUpload = false">{{ $t('取消') }}</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile" @click="submitUpload">{{ $t('上传并注册') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Upload } from '@element-plus/icons-vue'
import { listModels, activateModel, deleteModel, uploadModel, downloadModelUrl, renameModel } from '@/api/model'
import { t } from '@/i18n'

const router = useRouter()
const models = ref([])
const loading = ref(false)
const activatingId = ref(null)
const deletingId = ref(null)

const load = async () => {
  loading.value = true
  try {
    models.value = await listModels()
  } finally {
    loading.value = false
  }
}

/* 系列折叠树：按 seriesId 分组，初次训练/首次上传的根模型为父行，
   后续续训与同名上传版本为子行（默认折叠，点击展开） */
const treeModels = computed(() => {
  const byId = new Map()
  models.value.forEach(m => byId.set(m.id, { ...m, children: [] }))
  const roots = []
  byId.forEach(node => {
    const sid = node.seriesId ?? node.id
    if (sid === node.id) {
      roots.push(node)
    } else {
      const parent = byId.get(sid)
      if (parent) parent.children.push(node)
      else roots.push(node) // 系列根已删除时兜底平铺
    }
  })
  const byCreatedDesc = (a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))
  roots.forEach(r => {
    r.children.sort(byCreatedDesc)
    if (!r.children.length) delete r.children // 无子版本不显示展开箭头
  })
  return roots
})

/* 伪 ID：系列根行显示序号，子行留空 */
const rootIndexOf = (row) => {
  const i = treeModels.value.findIndex(m => m.id === row.id)
  return i >= 0 ? i + 1 : ''
}

const activate = async (row) => {
  try {
    await ElMessageBox.confirm(t('确认将版本 {version} 设为当前上线版本？', { version: row.version }), t('提示'), {
      type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消')
    })
  } catch {
    return
  }
  activatingId.value = row.id
  try {
    await activateModel(row.id)
    ElMessage.success(t('激活成功'))
    load()
  } finally {
    activatingId.value = null
  }
}

const goTest = (row) => router.push(`/models/${row.id}/test`)

/* 编辑模型名称 */
const rename = async (row) => {
  let value
  try {
    const r = await ElMessageBox.prompt(t('输入新的模型名称（最长 100 字符）'), `${t('编辑名称')} - ${row.version}`, {
      confirmButtonText: t('保存'),
      cancelButtonText: t('取消'),
      inputValue: row.name || '',
      inputValidator: (v) => (v && v.trim() ? true : t('名称不能为空')),
      inputPlaceholder: t('如：道路损害检测模型')
    })
    value = r.value
  } catch {
    return
  }
  await renameModel(row.id, value.trim())
  ElMessage.success(t('名称已更新'))
  load()
}

/* 浏览器直接下载权重文件（{版本号}.pt） */
const download = (row) => {
  const a = document.createElement('a')
  a.href = downloadModelUrl(row.id)
  a.download = `${row.version}.pt`
  document.body.appendChild(a)
  a.click()
  a.remove()
}

const goContinueTrain = (row) => router.push({ path: '/train', query: { baseVersionId: row.id } })

const doDelete = async (row) => {
  const extra = row.isActive === 1
    ? t('该模型当前已上线，删除后对外识别接口将没有默认模型！')
    : t('删除后模型记录与未共享的权重文件将被清理，不可恢复。')
  try {
    await ElMessageBox.confirm(`${t('确认删除模型版本 {version}？', { version: row.version })}${extra}`, t('提示'), {
      type: 'warning', confirmButtonText: t('确定'), cancelButtonText: t('取消')
    })
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteModel(row.id)
    ElMessage.success(t('删除成功'))
    load()
  } finally {
    deletingId.value = null
  }
}

const copyUuid = async (uuid) => {
  try {
    await navigator.clipboard.writeText(uuid)
    ElMessage.success(t('UUID 已复制'))
  } catch {
    ElMessage.warning(t('复制失败，请手动选择复制'))
  }
}

const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '-')

/* ---------- 上传模型 ---------- */
const showUpload = ref(false)
const uploading = ref(false)
const uploadFile = ref(null)
const uploadFileList = ref([])

const openUpload = () => {
  uploadFile.value = null
  uploadFileList.value = []
  showUpload.value = true
}
const onUploadFileChange = (file, fileList) => {
  if (!/\.pt$/i.test(file.name)) {
    ElMessage.warning(t('仅支持 .pt 格式的 YOLO 模型权重文件'))
    uploadFileList.value = []
    uploadFile.value = null
    return
  }
  uploadFile.value = file.raw
  uploadFileList.value = fileList.slice(-1)
}
const onUploadFileRemove = () => {
  uploadFile.value = null
  uploadFileList.value = []
}
const onUploadExceed = (files) => {
  uploadFile.value = files[0]
  uploadFileList.value = [{ name: files[0].name, raw: files[0] }]
}
const submitUpload = async () => {
  if (!uploadFile.value) {
    ElMessage.warning(t('请先选择 .pt 模型文件'))
    return
  }
  uploading.value = true
  try {
    const resp = await uploadModel(uploadFile.value)
    ElMessage.success(t('上传成功：版本 {version}，UUID 已自动生成', { version: resp.version }))
    showUpload.value = false
    load()
  } catch (e) {
    /* request.js 已提示 */
  } finally {
    uploading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h3 { font-size: 18px; }
.sub { color: #909399; font-size: 13px; }
.uuid-text { font-family: monospace; font-size: 12px; color: #606266; margin-right: 4px; }
.model-name { margin-right: 4px; }
:deep(.op-col .cell) { white-space: nowrap; }
</style>