<template>
  <div class="canvas-wrap" :style="{ maxWidth: width + 'px' }">
    <canvas
      ref="canvasRef"
      :width="width"
      :height="height"
      tabindex="0"
      @mousedown="onMouseDown"
      @mousemove="onMouseMove"
      @mouseup="onMouseUp"
      @mouseleave="onMouseUp"
      @dblclick="onDblClick"
      @keydown="onKeyDown"
    ></canvas>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'

const props = defineProps({
  imageUrl: { type: String, required: true },
  annotations: { type: Array, default: () => [] },
  width: { type: Number, default: 800 },
  height: { type: Number, default: 600 },
  activeLabelColor: { type: String, default: '#409EFF' },
  editable: { type: Boolean, default: true }
})

const emit = defineEmits(['select', 'create', 'update', 'remove', 'select-index'])

const canvasRef = ref(null)

// 交互模式：'idle' | 'draw' | 'move' | 'resize'
const mode = ref('idle')
const dragStart = ref({ x: 0, y: 0 })
const dragCurrent = ref({ x: 0, y: 0 })
const selectedIndex = ref(-1)
// resize：当前手柄方向 + 原始 box
const resizeHandle = ref('')
const resizeOriginal = ref(null)

const image = new Image()

watch(
  () => props.imageUrl,
  (url) => {
    if (url) {
      image.src = url
      image.onload = draw
    }
  },
  { immediate: true }
)

watch(
  () => props.annotations,
  () => {
    if (selectedIndex.value >= props.annotations.length) {
      selectedIndex.value = -1
    }
    draw()
  },
  { deep: true }
)

onMounted(async () => {
  await nextTick()
  draw()
})

/* ---------- 工具函数 ---------- */
function normalizePoint(e) {
  const canvas = canvasRef.value
  const rect = canvas.getBoundingClientRect()
  return {
    x: ((e.clientX - rect.left) / rect.width) * props.width,
    y: ((e.clientY - rect.top) / rect.height) * props.height
  }
}

function annToBox(a) {
  if (a.x2 != null) {
    return { x1: Number(a.x1), y1: Number(a.y1), x2: Number(a.x2), y2: Number(a.y2) }
  }
  const cx = Number(a.xCenter) * props.width
  const cy = Number(a.yCenter) * props.height
  const w = Number(a.width) * props.width
  const h = Number(a.height) * props.height
  return { x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2 }
}

function boxToAnn(box) {
  const x1 = Math.min(box.x1, box.x2)
  const y1 = Math.min(box.y1, box.y2)
  const x2 = Math.max(box.x1, box.x2)
  const y2 = Math.max(box.y1, box.y2)
  const w = x2 - x1
  const h = y2 - y1
  return {
    xCenter: (((x1 + x2) / 2) / props.width).toFixed(6),
    yCenter: (((y1 + y2) / 2) / props.height).toFixed(6),
    width: (w / props.width).toFixed(6),
    height: (h / props.height).toFixed(6)
  }
}

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v))
}

/* ---------- 命中检测 ---------- */
const HANDLE_SIZE = 8

function getHandles(box) {
  const cx = (box.x1 + box.x2) / 2
  const cy = (box.y1 + box.y2) / 2
  return [
    { name: 'nw', x: box.x1, y: box.y1 },
    { name: 'n',  x: cx,    y: box.y1 },
    { name: 'ne', x: box.x2, y: box.y1 },
    { name: 'e',  x: box.x2, y: cy },
    { name: 'se', x: box.x2, y: box.y2 },
    { name: 's',  x: cx,    y: box.y2 },
    { name: 'sw', x: box.x1, y: box.y2 },
    { name: 'w',  x: box.x1, y: cy }
  ]
}

function hitHandle(p, box) {
  const half = HANDLE_SIZE / 2 + 2
  for (const h of getHandles(box)) {
    if (Math.abs(p.x - h.x) <= half && Math.abs(p.y - h.y) <= half) return h.name
  }
  return ''
}

function hitBoxInterior(p, box) {
  const x1 = Math.min(box.x1, box.x2), x2 = Math.max(box.x1, box.x2)
  const y1 = Math.min(box.y1, box.y2), y2 = Math.max(box.y1, box.y2)
  return p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2
}

function hitTest(p) {
  // 优先：选中框的 handle → 任一框内部
  for (let i = props.annotations.length - 1; i >= 0; i--) {
    const a = props.annotations[i]
    if (a.labelId == null && a.x2 == null) continue
    const box = annToBox(a)
    if (i === selectedIndex.value) {
      const h = hitHandle(p, box)
      if (h) return { type: 'handle', handle: h, index: i }
    }
  }
  for (let i = props.annotations.length - 1; i >= 0; i--) {
    const a = props.annotations[i]
    if (a.labelId == null && a.x2 == null) continue
    const box = annToBox(a)
    if (hitBoxInterior(p, box)) return { type: 'box', index: i }
  }
  return { type: 'empty', index: -1 }
}

/* ---------- 鼠标事件 ---------- */
function onMouseDown(e) {
  if (!props.editable) return
  canvasRef.value?.focus()
  const p = normalizePoint(e)
  const hit = hitTest(p)
  if (hit.type === 'handle') {
    mode.value = 'resize'
    selectedIndex.value = hit.index
    resizeHandle.value = hit.handle
    resizeOriginal.value = { ...annToBox(props.annotations[hit.index]) }
    dragStart.value = p
    emit('select', props.annotations[hit.index], hit.index)
  } else if (hit.type === 'box') {
    selectedIndex.value = hit.index
    mode.value = 'move'
    dragStart.value = p
    emit('select', props.annotations[hit.index], hit.index)
    emit('select-index', hit.index)
  } else {
    mode.value = 'draw'
    dragStart.value = p
    dragCurrent.value = p
    selectedIndex.value = -1
    emit('select-index', -1)
  }
  draw()
}

function onMouseMove(e) {
  if (!props.editable) return
  const p = normalizePoint(e)
  if (mode.value === 'draw') {
    dragCurrent.value = p
    draw()
  } else if (mode.value === 'move' && selectedIndex.value >= 0) {
    const dx = p.x - dragStart.value.x
    const dy = p.y - dragStart.value.y
    const orig = annToBox(props.annotations[selectedIndex.value])
    // 拖动期间通过本地覆盖绘制
    tempOverride.value = {
      x1: orig.x1 + dx,
      y1: orig.y1 + dy,
      x2: orig.x2 + dx,
      y2: orig.y2 + dy
    }
    dragStart.value = p
    draw()
  } else if (mode.value === 'resize' && selectedIndex.value >= 0) {
    const o = resizeOriginal.value
    let { x1, y1, x2, y2 } = o
    const h = resizeHandle.value
    if (h.includes('w')) x1 = clamp(p.x, 0, props.width)
    if (h.includes('e')) x2 = clamp(p.x, 0, props.width)
    if (h.includes('n')) y1 = clamp(p.y, 0, props.height)
    if (h.includes('s')) y2 = clamp(p.y, 0, props.height)
    tempOverride.value = { x1, y1, x2, y2 }
    draw()
  } else {
    // 悬停时显示对应光标
    const hit = hitTest(p)
    const cursor = hit.type === 'handle'
      ? resizeCursor(hit.handle)
      : hit.type === 'box' ? 'move' : 'crosshair'
    canvasRef.value.style.cursor = cursor
  }
}

function resizeCursor(h) {
  if (h === 'nw' || h === 'se') return 'nwse-resize'
  if (h === 'ne' || h === 'sw') return 'nesw-resize'
  if (h === 'n' || h === 's') return 'ns-resize'
  if (h === 'e' || h === 'w') return 'ew-resize'
  return 'default'
}

const tempOverride = ref(null)

function onMouseUp() {
  if (!props.editable) return
  if (mode.value === 'draw') {
    const w = Math.abs(dragCurrent.value.x - dragStart.value.x)
    const h = Math.abs(dragCurrent.value.y - dragStart.value.y)
    if (w > 5 && h > 5) {
      const ann = boxToAnn({
        x1: dragStart.value.x,
        y1: dragStart.value.y,
        x2: dragCurrent.value.x,
        y2: dragCurrent.value.y
      })
      ann.labelId = 0
      ann.labelName = ''
      emit('create', ann)
    }
  } else if ((mode.value === 'move' || mode.value === 'resize') && selectedIndex.value >= 0 && tempOverride.value) {
    const newAnn = boxToAnn(tempOverride.value)
    const merged = { ...props.annotations[selectedIndex.value], ...newAnn }
    emit('update', selectedIndex.value, merged)
  }
  mode.value = 'idle'
  tempOverride.value = null
  resizeHandle.value = ''
  resizeOriginal.value = null
  draw()
}

function onDblClick(e) {
  if (!props.editable) return
  const p = normalizePoint(e)
  const hit = hitTest(p)
  if (hit.index >= 0) {
    emit('remove', props.annotations[hit.index], hit.index)
  }
}

function onKeyDown(e) {
  if (!props.editable) return
  if ((e.key === 'Delete' || e.key === 'Backspace') && selectedIndex.value >= 0) {
    e.preventDefault()
    emit('remove', props.annotations[selectedIndex.value], selectedIndex.value)
  } else if (e.key === 'Escape') {
    selectedIndex.value = -1
    emit('select-index', -1)
    draw()
  }
}

/* ---------- 绘制 ---------- */
function draw() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  const w = props.width
  const h = props.height
  ctx.clearRect(0, 0, w, h)
  if (image.complete && image.naturalWidth > 0) {
    ctx.drawImage(image, 0, 0, w, h)
  }

  const anns = props.annotations || []
  anns.forEach((a, idx) => {
    if (a.labelId == null && a.x2 == null) return
    let box
    if (idx === selectedIndex.value && tempOverride.value) {
      box = tempOverride.value
    } else {
      box = annToBox(a)
    }
    const x1 = Math.min(box.x1, box.x2), y1 = Math.min(box.y1, box.y2)
    const bw = Math.abs(box.x2 - box.x1), bh = Math.abs(box.y2 - box.y1)
    const color = a.labelColor || props.activeLabelColor || '#409EFF'
    const isActive = idx === selectedIndex.value
    ctx.strokeStyle = color
    ctx.lineWidth = isActive ? 3 : 2
    ctx.strokeRect(x1, y1, bw, bh)
    const label = a.labelName || a.label
    if (label) {
      ctx.fillStyle = color
      const text = `${label}${a.confidence != null ? ' ' + Number(a.confidence).toFixed(2) : ''}`
      ctx.font = '13px sans-serif'
      const tw = ctx.measureText(text).width + 8
      ctx.fillRect(x1, Math.max(0, y1 - 18), tw, 18)
      ctx.fillStyle = '#fff'
      ctx.fillText(text, x1 + 4, Math.max(13, y1 - 5))
    }
    // 选中时画 8 个手柄
    if (isActive && props.editable) {
      const handles = getHandles({ x1, y1, x2: x1 + bw, y2: y1 + bh })
      handles.forEach((hd) => {
        ctx.fillStyle = '#fff'
        ctx.strokeStyle = color
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.rect(hd.x - HANDLE_SIZE / 2, hd.y - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE)
        ctx.fill()
        ctx.stroke()
      })
    }
  })

  if (mode.value === 'draw') {
    const x = Math.min(dragStart.value.x, dragCurrent.value.x)
    const y = Math.min(dragStart.value.y, dragCurrent.value.y)
    const bw = Math.abs(dragCurrent.value.x - dragStart.value.x)
    const bh = Math.abs(dragCurrent.value.y - dragStart.value.y)
    ctx.strokeStyle = props.activeLabelColor
    ctx.lineWidth = 2
    ctx.setLineDash([6, 4])
    ctx.strokeRect(x, y, bw, bh)
    ctx.setLineDash([])
  }
}

function setSelected(idx) {
  selectedIndex.value = idx
  draw()
}

function renderDetections(detections) {
  const next = (detections || []).map((d) => ({ ...d }))
  emit('draw-detections', next)
}

defineExpose({ draw, renderDetections, setSelected })
</script>

<style scoped>
.canvas-wrap {
  position: relative;
  line-height: 0;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  overflow: hidden;
}
canvas {
  width: 100%;
  height: auto;
  cursor: crosshair;
  display: block;
  touch-action: none;
  outline: none;
}
</style>
