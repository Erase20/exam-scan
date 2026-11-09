<template>
  <div class="split-editor">
    <div class="se-toolbar">
      <el-button-group>
        <el-button size="small" :type="mode === 'draw' ? 'primary' : 'default'" @click="mode = 'draw'">
          画区
        </el-button>
        <el-button size="small" :type="mode === 'select' ? 'primary' : 'default'" @click="mode = 'select'">
          框选
        </el-button>
        <el-button size="small" :type="mode === 'adjust' ? 'primary' : 'default'" @click="mode = 'adjust'">
          调整
        </el-button>
      </el-button-group>
      <el-checkbox v-model="showLines" size="small" style="margin-left:10px">显示切分线</el-checkbox>
      <el-checkbox v-model="showBoxes" size="small">显示区块框</el-checkbox>
      <span class="se-hint">
        已选 {{ selected.length }} / {{ blocks.length }} 区块 · 图片 {{ imgW }}×{{ imgH }}
      </span>
      <div style="margin-left:auto;display:flex;gap:6px;flex-wrap:wrap">
        <el-button size="small" @click="selectAll">全选</el-button>
        <el-button size="small" :disabled="!selected.length" @click="clearSel">取消选择</el-button>
        <el-button size="small" :disabled="selected.length < 2" @click="mergeSelected">合并选中</el-button>
        <el-button size="small" :disabled="!selected.length" @click="keepOnlySelected">仅保留选中</el-button>
        <el-button size="small" :disabled="!selected.length" @click="markType('IMAGE')">标为图片</el-button>
        <el-button size="small" :disabled="!selected.length" @click="markType('TEXT')">标为文字</el-button>
        <el-button size="small" type="danger" :disabled="!selected.length" @click="removeSelected">删除选中</el-button>
        <el-button size="small" @click="resetBlocks">还原</el-button>
        <el-button size="small" type="primary" :loading="applying" :disabled="!blocks.length" @click="apply">
          应用切分（重新识别）
        </el-button>
      </div>
    </div>

    <div class="se-canvas" ref="wrapEl" :style="canvasStyle" @mousedown="onBgDown">
      <img :src="imageUrl" class="se-img" draggable="false" @load="onImgLoad" @error="imgError = true"
           @dragstart.prevent />
      <div v-if="imgError" class="se-imgerr">页面图片加载失败，请确认该文档已完成解析且图片仍存在</div>

      <!-- 区块框 -->
      <template v-if="showBoxes">
        <div v-for="(b, i) in blocks" :key="b._key || i" class="se-block" :class="[b.blockType, { sel: isSel(i) }]"
             :style="blockStyle(b)" @mousedown.stop="onBlockDown(i, $event)">
          <span class="se-tag">#{{ i + 1 }} {{ TYPE_LABEL[b.blockType] || b.blockType }}</span>
          <div v-if="mode === 'adjust'" class="se-handle h-top" @mousedown.stop="onResizeDown(i, 't', $event)"></div>
          <div v-if="mode === 'adjust'" class="se-handle h-left" @mousedown.stop="onResizeDown(i, 'l', $event)"></div>
          <div v-if="mode === 'adjust'" class="se-handle h-bottom" @mousedown.stop="onResizeDown(i, 'b', $event)"></div>
          <div v-if="mode === 'adjust'" class="se-handle h-right" @mousedown.stop="onResizeDown(i, 'r', $event)"></div>
        </div>
      </template>

      <!-- 切分边线：每个区块的下边界，可上下拖动 -->
      <template v-if="showLines">
        <div v-for="(l, i) in splitLines" :key="'l' + i" class="se-line" :style="lineStyle(l)"
             @mousedown.stop="onLineDrag(i, $event)">
          <span class="se-line-tip">切分线 {{ Math.round(l.y) }}</span>
        </div>
      </template>

      <!-- 范围选择矩形 -->
      <div v-if="selRect" class="se-selrect" :style="rectStyle"></div>
    </div>

    <div class="se-props">
      <template v-if="selected.length === 1">
        <span class="se-hint">区块 #{{ selected[0] + 1 }} 坐标微调：</span>
        <el-input-number v-model="cur.x" size="small" :min="0" :max="imgW" :step="5" controls-position="right" />
        <el-input-number v-model="cur.y" size="small" :min="0" :max="imgH" :step="5" controls-position="right" />
        <el-input-number v-model="cur.w" size="small" :min="10" :max="imgW" :step="5" controls-position="right" />
        <el-input-number v-model="cur.h" size="small" :min="10" :max="imgH" :step="5" controls-position="right" />
        <el-select v-model="cur.blockType" size="small" style="width:100px">
          <el-option v-for="t in ['TEXT', 'IMAGE', 'TABLE']" :key="t" :label="TYPE_LABEL[t]" :value="t" />
        </el-select>
      </template>
      <template v-else>
        <span class="se-hint">
          提示：「画区」在图上拖拽可新增切分区域；「框选」拖拽可选中范围内区块；「调整」拖动区块可整体移动、拖四条边（蓝条）可改大小、拖红色切分线可改分割位置。选中后按 Delete 键删除。
        </span>
      </template>
    </div>

    <div v-if="selected.length" class="se-preview">
      <el-tag v-for="i in selected" :key="i" size="small" closable @close="toggleSel(i, false)">
        #{{ i + 1 }}
      </el-tag>
      <span class="se-hint">已选区块内容：</span>
      <pre class="se-text">{{ selectedText }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'

const TYPE_LABEL = { TEXT: '文字', IMAGE: '图片', TABLE: '表格' }

const props = defineProps({
  imageUrl: String,
  // 后端返回区块（OCR 尺度坐标）
  sourceBlocks: { type: Array, default: () => [] },
  // 页面在 OCR 尺度下的尺寸
  pageSize: { type: Object, default: () => ({ width: 0, height: 0 }) },
  pageNo: { type: Number, default: 1 },
  applying: Boolean,
})
const emit = defineEmits(['apply', 'dirty'])

const wrapEl = ref(null)
const mode = ref('draw')          // draw: 画新区  select: 框选  adjust: 调整/移动
const showLines = ref(true)
const showBoxes = ref(true)
const selected = ref([])
const selRect = ref(null)
const blocks = ref([])
const imgW = ref(0)
const imgH = ref(0)
const imgError = ref(false)
const applying = computed(() => props.applying)

/** 把后端 OCR 尺度坐标换算到原图像素坐标 */
function toLocal(list) {
  const s = imgW.value && props.pageSize.width ? imgW.value / props.pageSize.width : 1
  return list.map((b, i) => ({
    _key: `${props.pageNo}-${b.id || i}`,
    blockType: b.blockType || 'TEXT',
    x: Math.round((b.x || 0) * s),
    y: Math.round((b.y || 0) * s),
    w: Math.round((b.w || 0) * s),
    h: Math.round((b.h || 0) * s),
    content: b.content || '',
  }))
}

watch([() => props.sourceBlocks, () => props.pageSize, () => props.imageUrl], () => {
  blocks.value = toLocal(props.sourceBlocks)
  selected.value = []
  selRect.value = null
  imgError.value = false
}, { immediate: true, deep: true })

/** 图片未加载时也按页面宽高比撑开画布，保证区块框与切分线可见 */
const canvasStyle = computed(() => {
  const w = imgW.value || props.pageSize.width
  const h = imgH.value || props.pageSize.height
  return w > 0 && h > 0 ? { aspectRatio: `${w} / ${h}` } : { minHeight: '200px' }
})

function onImgLoad(e) {
  imgW.value = e.target.naturalWidth || 0
  imgH.value = e.target.naturalHeight || 0
  blocks.value = toLocal(props.sourceBlocks)
}

// ---------- 坐标工具 ----------
function toImg(e) {
  const r = wrapEl.value.getBoundingClientRect()
  return {
    x: (e.clientX - r.left) / r.width * imgW.value,
    y: (e.clientY - r.top) / r.height * imgH.value,
  }
}
const pctX = v => (imgW.value ? (v / imgW.value * 100) + '%' : '0%')
const pctY = v => (imgH.value ? (v / imgH.value * 100) + '%' : '0%')
const clamp = (v, a, b) => Math.max(a, Math.min(b, v))

function blockStyle(b) {
  return { left: pctX(b.x), top: pctY(b.y), width: pctX(b.w), height: pctY(b.h) }
}

/** 切分边线：区块下边界（限定在该区块水平范围内） */
const splitLines = computed(() => blocks.value.map(b => ({
  y: b.y + b.h, x: b.x, w: b.w,
})))

function lineStyle(l) {
  return { left: pctX(l.x), top: pctY(l.y), width: pctX(l.w) }
}

const rectStyle = computed(() => {
  const s = norm(selRect.value)
  return { left: pctX(s.x1), top: pctY(s.y1), width: pctX(s.x2 - s.x1), height: pctY(s.y2 - s.y1) }
})

function norm(s) {
  if (!s) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  return {
    x1: Math.min(s.x1, s.x2), y1: Math.min(s.y1, s.y2),
    x2: Math.max(s.x1, s.x2), y2: Math.max(s.y1, s.y2),
  }
}

// ---------- 选择 ----------
const isSel = i => selected.value.includes(i)
function toggleSel(i, on) {
  if (on) {
    if (!selected.value.includes(i)) selected.value = [...selected.value, i]
  } else {
    selected.value = selected.value.filter(x => x !== i)
  }
}
function selectAll() { selected.value = blocks.value.map((_, i) => i) }
function clearSel() { selected.value = [] }

const cur = computed(() => blocks.value[selected.value[0]] || { x: 0, y: 0, w: 0, h: 0, blockType: 'TEXT' })
const selectedText = computed(() =>
  selected.value.map(i => (blocks.value[i]?.content || '').trim()).filter(Boolean).join('\n---\n'))

// ---------- 鼠标交互 ----------
let drag = null

function onBlockDown(i, e) {
  // 调整模式：按住区块本体可直接拖动移动位置（点一下不动则视为选中）
  if (mode.value === 'adjust') {
    const b = blocks.value[i]
    if (b) {
      selected.value = [i]
      drag = { type: 'move', idx: i, start: toImg(e), ox: b.x, oy: b.y, moved: false }
      bindMove()
    }
    return
  }
  const add = e.ctrlKey || e.shiftKey || e.metaKey
  if (add) toggleSel(i, !isSel(i))
  else selected.value = [i]
}

/** 拖动切分线（区块下边界）：上方块变高/变矮，下方相邻块跟随 */
function onLineDrag(idx, e) {
  e.preventDefault()
  if (!blocks.value[idx]) return
  drag = { type: 'line', idx, bottom: blocks.value[idx].y + blocks.value[idx].h }
  bindMove()
}

/** 拖动区块的下边/右边手柄 */
function onResizeDown(i, edge, e) {
  e.preventDefault()
  drag = { type: 'resize', idx: i, edge, bottom: blocks.value[i].y + blocks.value[i].h }
  bindMove()
}

/** 背景拖拽：画区模式用于新建区域，框选模式用于范围选择 */
function onBgDown(e) {
  if (mode.value === 'adjust') return
  if (e.target !== wrapEl.value && e.target.classList.contains('se-img') === false) return
  const p = toImg(e)
  selRect.value = { x1: p.x, y1: p.y, x2: p.x, y2: p.y }
  drag = { type: 'rect', action: mode.value, start: p }
  bindMove()
}

function onMove(e) {
  if (!drag) return
  const p = toImg(e)
  if (drag.type === 'rect') {
    selRect.value = { ...selRect.value, x2: p.x, y2: p.y }
    return
  }
  if (drag.type === 'move') {
    const b = blocks.value[drag.idx]
    if (!b) return
    const dx = p.x - drag.start.x, dy = p.y - drag.start.y
    if (!drag.moved && Math.abs(dx) < 3 && Math.abs(dy) < 3) return
    drag.moved = true
    b.x = clamp(Math.round(drag.ox + dx), 0, imgW.value - b.w)
    b.y = clamp(Math.round(drag.oy + dy), 0, imgH.value - b.h)
    dirty()
    return
  }
  if (drag.type === 'line' || drag.type === 'resize') {
    const b = blocks.value[drag.idx]
    if (!b) return
    if (drag.type === 'line' || drag.edge === 'b') {
      setSplitY(drag.idx, p.y)
    } else if (drag.edge === 't') {
      const bottom = b.y + b.h
      const ny = clamp(Math.round(p.y), 0, bottom - 10)
      b.y = ny
      b.h = bottom - ny
      dirty()
    } else if (drag.edge === 'l') {
      const right = b.x + b.w
      const nx = clamp(Math.round(p.x), 0, right - 10)
      b.x = nx
      b.w = right - nx
      dirty()
    } else {
      b.w = clamp(Math.round(p.x - b.x), 10, imgW.value - b.x)
      dirty()
    }
  }
}

/** 设置第 idx 个区块的下边界，并让下方相邻（水平重叠）区块跟随 */
function setSplitY(idx, y) {
  const b = blocks.value[idx]
  if (!b) return
  const ny = clamp(Math.round(y), b.y + 10, imgH.value)
  const bottom = b.y + b.h
  b.h = ny - b.y
  // 下方紧邻且水平重叠的区块：顶边跟随，底边保持不变（保证不丢内容）
  let j = -1
  let bestGap = Infinity
  blocks.value.forEach((o, k) => {
    if (k === idx) return
    if (o.y + 2 < bottom) return                       // 必须在其下方
    if (o.y - bottom > Math.max(80, b.h)) return       // 距离太远不算相邻
    const ov = Math.min(b.x + b.w, o.x + o.w) - Math.max(b.x, o.x)
    if (ov <= Math.min(b.w, o.w) * 0.3) return         // 水平不重叠
    const gap = o.y - bottom
    if (gap < bestGap) { bestGap = gap; j = k }
  })
  if (j >= 0) {
    const o = blocks.value[j]
    const ob = o.y + o.h
    o.y = Math.min(ny, ob - 10)
    o.h = Math.max(10, ob - o.y)
  }
  dirty()
}

function onUp() {
  if (!drag) return
  if (drag.type === 'rect' && selRect.value) {
    const s = norm(selRect.value)
    if (drag.action === 'draw') {
      createRegion(s)
    } else if (s.x2 - s.x1 < 4 || s.y2 - s.y1 < 4) {
      selRect.value = null
    } else {
      // 框选：与矩形有交集的区块被选中
      const hits = []
      blocks.value.forEach((b, i) => {
        const ox = Math.min(b.x + b.w, s.x2) - Math.max(b.x, s.x1)
        const oy = Math.min(b.y + b.h, s.y2) - Math.max(b.y, s.y1)
        if (ox > 0 && oy > 0) hits.push(i)
      })
      selected.value = hits
      ElMessage.success(hits.length ? `已框选 ${hits.length} 个区块` : '该范围内没有区块')
      selRect.value = null
    }
  }
  drag = null
  unbindMove()
}

function bindMove() {
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}
function unbindMove() {
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseup', onUp)
}

/** 鼠标拖拽新增切分区域 */
function createRegion(s) {
  const w = s.x2 - s.x1, h = s.y2 - s.y1
  selRect.value = null
  if (w < 12 || h < 12) {
    ElMessage.warning('区域太小，请拖拽出更大的范围')
    return
  }
  blocks.value.push({
    _key: 'n' + Date.now(),
    blockType: 'TEXT',
    x: Math.round(s.x1), y: Math.round(s.y1),
    w: Math.round(w), h: Math.round(h),
    content: '',
  })
  selected.value = [blocks.value.length - 1]
  ElMessage.success('已新增切分区域，点「应用切分」识别该区域')
  dirty()
}

/** Delete/Backspace 删除选中的切分区域 */
function onKeyDown(e) {
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  const t = e.target
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  if (!selected.value.length) return
  e.preventDefault()
  removeSelected()
  ElMessage.success('已删除选中的切分区域')
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => {
  unbindMove()
  window.removeEventListener('keydown', onKeyDown)
})

function dirty() { emit('dirty') }

// ---------- 区域操作 ----------
function mergeSelected() {
  if (selected.value.length < 2) return
  const list = selected.value.map(i => blocks.value[i]).filter(Boolean)
  const x = Math.min(...list.map(b => b.x))
  const y = Math.min(...list.map(b => b.y))
  const x2 = Math.max(...list.map(b => b.x + b.w))
  const y2 = Math.max(...list.map(b => b.y + b.h))
  const merged = {
    _key: 'm' + Date.now(),
    blockType: list.some(b => b.blockType === 'IMAGE') ? 'IMAGE' : 'TEXT',
    x, y, w: x2 - x, h: y2 - y,
    content: list.map(b => b.content).filter(Boolean).join('\n'),
  }
  const keepIdx = selected.value[0]
  const rest = blocks.value.filter((_, i) => !selected.value.includes(i))
  rest.splice(keepIdx, 0, merged)
  blocks.value = rest
  selected.value = [keepIdx]
  ElMessage.success(`已合并 ${list.length} 个区块`)
  dirty()
}

function removeSelected() {
  blocks.value = blocks.value.filter((_, i) => !selected.value.includes(i))
  selected.value = []
  dirty()
}

function keepOnlySelected() {
  blocks.value = selected.value.map(i => blocks.value[i]).filter(Boolean)
  selected.value = []
  dirty()
}

function markType(t) {
  selected.value.forEach(i => { if (blocks.value[i]) blocks.value[i].blockType = t })
  dirty()
}

function resetBlocks() {
  blocks.value = toLocal(props.sourceBlocks)
  selected.value = []
  selRect.value = null
  emit('dirty')
}

function apply() {
  if (!blocks.value.length) {
    ElMessage.warning('没有可识别的区块')
    return
  }
  const regions = blocks.value.map(b => ({
    pageNo: props.pageNo,
    blockType: b.blockType,
    x: Math.round(b.x), y: Math.round(b.y),
    w: Math.round(b.w), h: Math.round(b.h),
  }))
  emit('apply', regions)
}
</script>

<style scoped>
.split-editor { border: 1px solid #e4e7ed; border-radius: 6px; padding: 10px; background: #fff; }
.se-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.se-hint { font-size: 12px; color: #909399; }
.se-canvas { position: relative; user-select: none; border: 1px dashed #dcdfe6; background: #fafafa; width: 100%; }
.se-imgerr { padding: 10px; font-size: 12px; color: #f56c6c; background: #fef0f0; }
.se-img { width: 100%; display: block; }
.se-block { position: absolute; border: 2px solid; box-sizing: border-box; cursor: pointer; }
.se-block.TEXT { border-color: #409EFF; background: rgba(64, 158, 255, .08); }
.se-block.IMAGE { border-color: #E6A23C; background: rgba(230, 162, 60, .12); }
.se-block.TABLE { border-color: #67C23A; background: rgba(103, 194, 58, .12); }
.se-block.sel { outline: 2px dashed #f56c6c; background: rgba(245, 108, 108, .18); z-index: 3; }
.se-tag { position: absolute; top: -16px; left: -2px; font-size: 10px; background: #fff; padding: 0 3px; line-height: 14px; border-radius: 2px; }
.se-handle { position: absolute; background: #409EFF; opacity: .75; }
.se-handle.h-bottom { left: 0; right: 0; bottom: -4px; height: 8px; cursor: ns-resize; }
.se-handle.h-right { top: 0; bottom: 0; right: -4px; width: 8px; cursor: ew-resize; }
.se-handle.h-top { left: 0; right: 0; top: -4px; height: 8px; cursor: ns-resize; }
.se-handle.h-left { top: 0; bottom: 0; left: -4px; width: 8px; cursor: ew-resize; }
.se-line { position: absolute; height: 0; border-top: 2px dashed #f56c6c; cursor: ns-resize; z-index: 4; }
.se-line::before { content: ''; position: absolute; left: 0; right: 0; top: -6px; height: 12px; }
.se-line-tip { position: absolute; right: 0; top: -16px; font-size: 10px; color: #f56c6c; background: #fff; padding: 0 3px; border-radius: 2px; }
.se-selrect { position: absolute; border: 1px solid #409EFF; background: rgba(64, 158, 255, .15); z-index: 5; pointer-events: none; }
.se-props { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.se-preview { margin-top: 8px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.se-text { margin: 6px 0 0; width: 100%; max-height: 120px; overflow: auto; font-size: 12px; line-height: 1.6; background: #f5f7fa; padding: 6px; border-radius: 4px; white-space: pre-wrap; }
</style>
