<template>
  <div>
    <h2 class="page-title">扫描识别</h2>
    <div class="page-card">
      <el-alert v-if="!ocrOk" type="warning" :closable="false" style="margin-bottom:12px"
                title="OCR 服务未连接，将使用演示数据模拟识别结果（启动 ocr-service 后自动切换为真实识别）" />
      <el-form inline>
        <el-form-item label="试卷名称"><el-input v-model="docName" placeholder="如：六年级科学学期末卷" style="width:220px" /></el-form-item>
        <el-form-item label="学科">
          <el-select v-model="subject" style="width:120px">
            <el-option v-for="s in ['数学','语文','英语','物理','化学','科学']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="年级">
          <el-select v-model="grade" style="width:120px">
            <el-option v-for="g in ['一年级','二年级','三年级','四年级','五年级','六年级','七年级','八年级','九年级']" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-upload drag multiple accept="image/*" :auto-upload="false" :on-change="onFile" :file-list="fileList"
                 list-type="picture">
        <el-icon size="48" style="color:#909399"><UploadFilled /></el-icon>
        <div>拖拽试卷扫描件到此处，或<em>点击选择图片</em></div>
      </el-upload>
      <div style="margin-top:14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap">
        <el-radio-group v-model="scanMode" size="large">
          <el-radio-button value="manual">框选区域识别（快）</el-radio-button>
          <el-radio-button value="auto">整卷自动识别（慢）</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="large" :loading="scanning" :disabled="!fileList.length" @click="doScan">
          {{ scanning ? '处理中…' : (scanMode === 'manual' ? '上传并进入框选' : '开始扫描识别') }}
        </el-button>
        <el-button size="large" @click="openShot">📷 截屏识别</el-button>
        <span style="font-size:12px;color:#909399">
          {{ scanMode === 'manual'
            ? '上传后直接在页面图上拖框选题目区域，只识别框内内容，速度快、结果可控'
            : '自动做整页版面分析与识别，页数多时耗时较长' }}
        </span>
      </div>
    </div>

    <!-- 截屏识别对话框 -->
    <el-dialog v-model="shotDlg" title="📷 截屏识别" width="880px" @open="bindPaste" @close="closeShot">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="方式一：点「开始屏幕截取」选择窗口/屏幕后抓取画面；方式二：直接按 Ctrl+V 粘贴截图。截取后可拖动框选只识别题目区域。" />
      <div style="margin-bottom:12px">
        <el-button type="primary" :loading="sharing" @click="startShare">开始屏幕截取</el-button>
        <el-button v-if="streaming" type="success" @click="grabFrame">截取当前画面</el-button>
        <el-button v-if="shotImage" @click="resetSel" :disabled="!sel">重选区域</el-button>
        <el-button v-if="shotImage" type="success" :disabled="!sel" @click="useCrop">使用选区加入列表</el-button>
        <el-button v-if="shotImage" @click="useWhole">使用整图加入列表</el-button>
      </div>
      <video v-if="streaming" ref="videoEl" autoplay playsinline muted
             style="width:100%;max-height:280px;border:1px solid #ddd;background:#000"></video>
      <div v-if="shotImage" style="max-height:420px;overflow:auto;border:1px solid #eee">
        <canvas ref="canvasEl" class="shot-canvas"
                @mousedown="selStart" @mousemove="selMove" @mouseup="selEnd" @mouseleave="selEnd" />
      </div>
      <el-empty v-if="!streaming && !shotImage" description="等待屏幕截取或粘贴截图…" :image-size="70" />
    </el-dialog>

    <div v-if="result" class="page-card">
      <h3 style="margin-top:0">识别结果</h3>
      <el-alert v-if="result.warning" type="warning" :closable="false" show-icon
                style="margin-bottom:12px" :title="result.warning" />
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="页数">{{ result.pageCount }}</el-descriptions-item>
        <el-descriptions-item label="版面区块">{{ result.blockCount }}</el-descriptions-item>
        <el-descriptions-item label="切出题目">{{ result.questionCount }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ result.costMs }} ms</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="!result.blockCount" type="success" :closable="false" style="margin-top:12px"
                title="已进入框选模式：在下方「切分调整」里用「画区」在页面图上拖框选题目区域（可画多个），然后点「应用切分（重新识别）」，系统只识别框内内容并按题号切题。" />

      <el-tabs v-model="tabName" style="margin-top:12px">
        <el-tab-pane label="切分调整" name="split">
          <div v-if="!pageImageUrl" class="se-empty">暂无可编辑的页面（请先完成一次识别）</div>
          <template v-else>
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
              <span style="font-size:13px;color:#606266">页码</span>
              <el-select v-model="curPageNo" size="small" style="width:100px">
                <el-option v-for="p in (result.pages || [])" :key="p.pageNo" :label="`第 ${p.pageNo} 页`" :value="p.pageNo" />
              </el-select>
              <el-tag v-if="splitDirty" type="warning" size="small">已调整，尚未应用</el-tag>
              <el-button size="small" @click="reloadPageImage">刷新页面图</el-button>
            </div>
            <SplitEditor :image-url="pageImageUrl" :source-blocks="curBlocks" :page-size="curPageSize"
                         :page-no="curPageNo" :applying="splitApplying" @apply="onApplySplit" @dirty="splitDirty = true" />
          </template>
        </el-tab-pane>
        <el-tab-pane label="版面分区可视化" name="layout">
          <div class="layout-preview">
            <img v-if="pageImageUrl" :src="pageImageUrl" class="page-img" />
            <div v-for="(b, i) in curBlocks" :key="i" class="block-box" :class="b.blockType"
                 :style="boxStyle(b)">
              <span class="block-tag">{{ b.blockType }} #{{ b.sortNo }}</span>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`题目列表（${questions.length}）`" name="questions">
          <el-alert v-if="questions.length"
                    :type="imported ? 'success' : 'warning'" :closable="false" show-icon
                    style="margin-bottom:10px">
            {{ imported
              ? '当前题目已确认入库；可继续编辑或给无图题「加图」，再次修改草稿或重新切分后需重新确认。'
              : '以下为切分草稿，尚未写入题库；请核对（可修改 / 复制 / 拆分 / 合并 / 删除 / 加图），点「确认入库」后写入题库。' }}
          </el-alert>
          <div style="margin-bottom:10px">
            <el-button type="primary" :loading="confirmLoading" :disabled="!questions.length"
                       @click="confirmImport">确认入库（{{ questions.length }} 题）</el-button>
          </div>
          <div v-for="g in groupedQuestions" :key="g.key" class="qgroup">
            <div class="qgroup-title">
              <span v-if="g.sectionNo" class="qgroup-no">{{ g.sectionNo }}、</span>
              <span class="qgroup-name">{{ g.title }}</span>
              <el-tag size="small" type="info">{{ g.list.length }} 题</el-tag>
            </div>
            <el-table :data="g.list" size="small">
              <el-table-column prop="qNo" label="题号" width="70" />
              <el-table-column label="题型" width="100">
                <template #default="{ row }"><el-tag size="small">{{ typeLabel(row.qType) }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="stem" label="题干" show-overflow-tooltip />
              <el-table-column label="选项" width="80">
                <template #default="{ row }">{{ (row.options || []).length || '-' }}</template>
              </el-table-column>
              <el-table-column label="配图" width="76">
                <template #default="{ row }">
                  <el-image v-if="imgList(row.imagePath).length" :src="imgList(row.imagePath)[0]"
                            :preview-src-list="imgList(row.imagePath)" preview-teleported fit="cover"
                            style="width:40px;height:40px;border-radius:3px;border:1px solid #eee" />
                  <span v-else style="color:#c0c4cc;font-size:12px">无</span>
                </template>
              </el-table-column>
              <el-table-column prop="difficulty" label="难度" width="70" />
              <el-table-column prop="score" label="分值" width="70" />
              <el-table-column label="操作" width="330">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="editDraft(row)">编辑</el-button>
                  <el-upload :show-file-list="false" :http-request="opt => addDraftImage(opt, row)"
                             accept="image/png,image/jpeg,image/gif,image/bmp" style="display:inline-block">
                    <el-button size="small" text type="success">加图</el-button>
                  </el-upload>
                  <el-button size="small" text @click="copyDraft(row)">复制</el-button>
                  <el-button size="small" text type="warning" @click="splitDraft(row)">拆分</el-button>
                  <el-button size="small" text @click="mergeDraft(row)">合并下题</el-button>
                  <el-button size="small" text type="danger" @click="removeDraft(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
        <el-tab-pane label="OCR 全文" name="fulltext">
          <pre class="fulltext">{{ fullText }}</pre>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div class="page-card">
      <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
        <h3 style="margin:0 0 4px;flex:1;min-width:120px">
          历史文档 <el-button size="small" text type="primary" @click="loadDocs">刷新</el-button>
        </h3>
        <el-button size="small" type="danger" :disabled="!docSel.length" @click="delDocsBatch">
          🗑 批量删除{{ docSel.length ? `（${docSel.length}）` : '' }}
        </el-button>
      </div>
      <el-table ref="docsTableRef" :data="docs" size="small" @selection-change="s => docSel = s">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="docName" label="名称" />
        <el-table-column label="原图" width="76" class-name="col-optional">
          <template #default="{ row }">
            <el-image v-if="row.sourceFile" :src="docThumb(row.id)"
                      :preview-src-list="[docThumb(row.id)]" preview-teleported fit="cover"
                      style="width:40px;height:52px;border-radius:3px;border:1px solid #eee" />
            <span v-else style="color:#c0c4cc;font-size:12px">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="学科" width="90" class-name="col-optional" />
        <el-table-column prop="grade" label="年级" width="90" class-name="col-optional" />
        <el-table-column prop="pageCount" label="页数" width="70" class-name="col-optional" />
        <el-table-column prop="status" label="状态" width="110" class-name="col-optional">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PARSED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="parseMs" label="耗时(ms)" width="90" class-name="col-optional" />
        <el-table-column label="识别时间" width="145" class-name="col-optional">
          <template #default="{ row }">{{ (row.createTime || '').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click="openDoc(row.id)">打开原图</el-button>
            <el-button size="small" @click="reparse(row.id)">重新解析</el-button>
            <el-button size="small" type="danger" text @click="delDoc(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <QuestionEditDialog v-model="draftEditVisible" :question="draftTarget" draft @saved="onDraftSaved" />

    <el-dialog v-model="splitVisible" title="拆分为多题" width="620">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
                title="用空行或 --- 分隔每道题；拆分后每题可在「编辑」里补全选项与答案。" />
      <el-input v-model="splitText" type="textarea" :rows="14" />
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSplit">拆分</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, onBeforeUnmount, watch } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'
import SplitEditor from '../components/SplitEditor.vue'
import QuestionEditDialog from '../components/QuestionEditDialog.vue'

const docName = ref(''); const subject = ref('科学'); const grade = ref('六年级')
const fileList = ref([]); const scanning = ref(false); const result = ref(null)
const scanMode = ref('manual')     // manual: 上传后框选区域  auto: 整卷自动识别
const tabName = ref('split')
const docs = ref([]); const ocrOk = ref(false)
const imported = ref(false); const confirmLoading = ref(false)
const draftEditVisible = ref(false); const draftTarget = ref(null)
const splitVisible = ref(false); const splitTarget = ref(null); const splitText = ref('')
const questions = computed(() => result.value?.questions || [])
/** 按题型大类（一、填空题 / 二、判断题 …）分组，组内按题号排序 */
const groupedQuestions = computed(() => {
  const groups = new Map()
  for (const q of questions.value) {
    const key = q.sectionOrder ?? 0
    if (!groups.has(key)) {
      groups.set(key, { key, sectionNo: q.sectionNo || '', title: '', list: [] })
    }
    const g = groups.get(key)
    if (!g.title) g.title = q.sectionTitle || typeLabel(q.qType) || '题目'
    g.list.push(q)
  }
  return [...groups.values()].map(g => ({
    ...g,
    list: [...g.list].sort((a, b) => (parseInt(a.qNo) || 0) - (parseInt(b.qNo) || 0)),
  }))
})
const blocks = computed(() => {
  const all = []
  for (const b of result.value?.blocks || []) all.push(b)
  return all
})
const fullText = ref('')
const typeLabel = t => Q_TYPE_LABELS[t] || t

// ---------- 切分调整 ----------
const curPageNo = ref(1)
const imgVer = ref(0)
const splitApplying = ref(false)
const splitDirty = ref(false)
// 注意：<img src> 不会走 axios 的 baseURL，这里必须写全 /api 前缀
const pageImageUrl = computed(() => result.value?.docId
  ? `/api/scan/docs/${result.value.docId}/pages/${curPageNo.value}/image?v=${imgVer.value}` : '')
const curPageSize = computed(() => (result.value?.pages || []).find(p => p.pageNo === curPageNo.value)
  || { width: 0, height: 0 })
const curBlocks = computed(() => (result.value?.blocks || []).filter(b => b.pageNo === curPageNo.value))

watch(() => result.value?.docId, () => {
  curPageNo.value = 1
  imgVer.value++
  splitDirty.value = false
  imported.value = false
})

const docThumbVer = ref(0)
const docThumb = id => `/api/scan/docs/${id}/pages/1/image?v=${docThumbVer.value}`
function reloadPageImage() { imgVer.value++; docThumbVer.value++ }

async function onApplySplit(regions) {
  const docId = result.value?.docId
  if (!docId) return
  splitApplying.value = true
  try {
    const r = await http.post(`/scan/docs/${docId}/resplit`, { regions, autoSave: false })
    result.value = { ...result.value, ...r }
    splitDirty.value = false
    imported.value = false
    tabName.value = 'questions'
    ElMessage.success(`已按调整后的 ${r.blockCount} 个区域重新识别，切出 ${r.questionCount} 道题（草稿）`)
    loadDocs()
  } catch (e) {
    ElMessage.error('应用切分失败：' + (e?.message || e))
  } finally {
    splitApplying.value = false
  }
}

function onFile(file, files) { fileList.value = files }

// ---------- 截屏识别 ----------
const shotDlg = ref(false); const sharing = ref(false); const streaming = ref(false)
const videoEl = ref(null); const canvasEl = ref(null)
const shotImage = ref(null)          // HTMLCanvasElement，截取/粘贴到的原图
const sel = ref(null)                // 框选区域（canvas 像素坐标）
let dragging = false
let screenStream = null

function openShot() { shotDlg.value = true }

function bindPaste() {
  window.addEventListener('paste', onPaste)
  window.addEventListener('keydown', onShotKeydown)
}

function closeShot() {
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('keydown', onShotKeydown)
  stopStream()
  shotImage.value = null
  sel.value = null
}

let lastPasteAt = 0

/** 从剪贴板数据里找图片：兼容截图工具的 Files 类型 */
function findImageInClipboard(dt) {
  const files = dt?.files || []
  for (const f of files) {
    if (f.type.startsWith('image/')) return f
  }
  const items = dt?.items || []
  for (const it of items) {
    if (it.kind === 'file' && it.type.startsWith('image/')) {
      const f = it.getAsFile()
      if (f) return f
    }
  }
  return null
}

function onPaste(e) {
  if (!shotDlg.value) return
  lastPasteAt = Date.now()
  const img = findImageInClipboard(e.clipboardData)
  if (img) {
    loadAsImage(img, '粘贴的截图')
  } else {
    ElMessage.warning('剪贴板中没有图片内容（请先截图复制，或复制的是文字）')
  }
}

/** Ctrl+V 兜底：paste 事件未携带图片时走异步剪贴板 API */
function onShotKeydown(e) {
  if (!shotDlg.value) return
  if ((e.ctrlKey || e.metaKey) && (e.key === 'v' || e.key === 'V')) {
    setTimeout(async () => {
      if (Date.now() - lastPasteAt < 800) return  // paste 事件已处理
      try {
        if (!navigator.clipboard?.read) return
        const items = await navigator.clipboard.read()
        for (const it of items) {
          const t = (it.types || []).find(x => x.startsWith('image/'))
          if (t) {
            const blob = await it.getType(t)
            loadAsImage(blob, '粘贴的截图')
            return
          }
        }
      } catch (err) {
        // 剪贴板读取权限被拒时静默，paste 事件路径仍可用
      }
    }, 150)
  }
}

function loadAsImage(blob, label) {
  const url = URL.createObjectURL(blob)
  const img = new Image()
  img.onload = () => {
    const c = document.createElement('canvas')
    c.width = img.naturalWidth; c.height = img.naturalHeight
    c.getContext('2d').drawImage(img, 0, 0)
    shotImage.value = c
    sel.value = null
    nextTick(drawShot)
    URL.revokeObjectURL(url)
    ElMessage.success(`${label}已载入（${c.width}×${c.height}），可拖动框选区域`)
  }
  img.src = url
}

async function startShare() {
  sharing.value = true
  try {
    screenStream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 15 } })
    streaming.value = true
    await nextTick()
    videoEl.value.srcObject = screenStream
    screenStream.getVideoTracks()[0].addEventListener('ended', () => { streaming.value = false })
  } catch (e) {
    if (e?.name !== 'NotAllowedError') ElMessage.error('屏幕截取失败：' + (e?.message || e))
  } finally { sharing.value = false }
}

function stopStream() {
  screenStream?.getTracks().forEach(t => t.stop())
  screenStream = null
  streaming.value = false
}

function grabFrame() {
  const v = videoEl.value
  if (!v || !v.videoWidth) { ElMessage.warning('画面尚未就绪'); return }
  const c = document.createElement('canvas')
  c.width = v.videoWidth; c.height = v.videoHeight
  c.getContext('2d').drawImage(v, 0, 0)
  shotImage.value = c
  sel.value = null
  stopStream()
  nextTick(drawShot)
  ElMessage.success(`已截取画面（${c.width}×${c.height}），可拖动框选区域`)
}

function norm(s) {
  return { x1: Math.min(s.x1, s.x2), y1: Math.min(s.y1, s.y2), x2: Math.max(s.x1, s.x2), y2: Math.max(s.y1, s.y2) }
}

function drawShot() {
  const c = canvasEl.value, img = shotImage.value
  if (!c || !img) return
  c.width = img.width; c.height = img.height
  const ctx = c.getContext('2d')
  ctx.drawImage(img, 0, 0)
  if (sel.value) {
    const s = norm(sel.value)
    ctx.fillStyle = 'rgba(0,0,0,0.45)'
    ctx.fillRect(0, 0, c.width, s.y1)
    ctx.fillRect(0, s.y2, c.width, c.height - s.y2)
    ctx.fillRect(0, s.y1, s.x1, s.y2 - s.y1)
    ctx.fillRect(s.x2, s.y1, c.width - s.x2, s.y2 - s.y1)
    ctx.strokeStyle = '#409EFF'; ctx.lineWidth = Math.max(2, c.width / 600)
    ctx.strokeRect(s.x1, s.y1, s.x2 - s.x1, s.y2 - s.y1)
  }
}

function toCanvas(e) {
  const r = canvasEl.value.getBoundingClientRect()
  return { x: Math.round((e.clientX - r.left) * canvasEl.value.width / r.width),
           y: Math.round((e.clientY - r.top) * canvasEl.value.height / r.height) }
}

function selStart(e) { if (!shotImage.value) return; e.preventDefault(); dragging = true; const p = toCanvas(e); sel.value = { x1: p.x, y1: p.y, x2: p.x, y2: p.y } }
function selMove(e) { if (!dragging) return; const p = toCanvas(e); sel.value = { ...sel.value, x2: p.x, y2: p.y }; drawShot() }
function selEnd(e) { if (!dragging) return; dragging = false; const p = toCanvas(e); sel.value = { ...sel.value, x2: p.x, y2: p.y }; drawShot() }

function resetSel() { sel.value = null; drawShot() }

function stamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function addShotToFiles(canvas, label) {
  canvas.toBlob(blob => {
    if (!blob) { ElMessage.error('生成截图失败'); return }
    const file = new File([blob], `截屏-${stamp()}.png`, { type: 'image/png' })
    fileList.value = [...fileList.value, { name: file.name, raw: file, uid: Date.now(), status: 'ready' }]
    if (!docName.value) docName.value = `截屏试卷-${stamp()}`
    ElMessage.success(`${label}已加入识别列表（${canvas.width}×${canvas.height}），点「开始扫描识别」即可`)
    shotDlg.value = false
  }, 'image/png')
}

function useCrop() {
  if (!sel.value) return
  const s = norm(sel.value)
  if (s.x2 - s.x1 < 20 || s.y2 - s.y1 < 20) { ElMessage.warning('选区太小，请重新框选'); return }
  const c = document.createElement('canvas')
  c.width = s.x2 - s.x1; c.height = s.y2 - s.y1
  c.getContext('2d').drawImage(shotImage.value, s.x1, s.y1, c.width, c.height, 0, 0, c.width, c.height)
  addShotToFiles(c, '选区截图')
}

function useWhole() { addShotToFiles(shotImage.value, '整图截图') }

/** 框选模式：只上传登记页面，不跑整卷 OCR，秒级进入框选 */
async function doUploadForSelect() {
  const fd = new FormData()
  fileList.value.forEach(f => fd.append('files', f.raw))
  fd.append('docName', docName.value)
  fd.append('subject', subject.value)
  fd.append('grade', grade.value)
  const doc = await http.post('/scan/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  const r = await http.post(`/scan/docs/${doc.id}/prepare`)
  result.value = r
  await loadDetail()
  tabName.value = 'split'
  ElMessage.success('已上传，请在页面图上框选题目区域后「应用切分」')
}

async function doScan() {
  scanning.value = true
  try {
    if (scanMode.value === 'manual') {
      await doUploadForSelect()
      loadDocs()
      return
    }
    const fd = new FormData()
    fileList.value.forEach(f => fd.append('files', f.raw))
    fd.append('docName', docName.value)
    fd.append('subject', subject.value)
    fd.append('grade', grade.value)
    fd.append('autoSave', 'false')
    result.value = await http.post('/scan/recognize', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    imported.value = false
    ElMessage.success(`识别完成：切出 ${result.value.questionCount} 道题（草稿），请核对后确认入库`)
    await loadDetail()
    loadDocs()
  } finally {
    scanning.value = false
  }
}

async function loadDetail() {
  fullText.value = result.value?.fullText || ''
  curPageNo.value = 1
  imgVer.value++
  splitDirty.value = false
}

/** 打开历史文档（含手机拍照上传）：直接显示页面原图，无需先跑整卷 OCR */
async function openDoc(docId) {
  try {
    const d = await http.get(`/scan/docs/${docId}`)
    const pages = (d.pages || []).map(p => ({
      id: p.id, pageNo: p.pageNo, width: p.width || 0, height: p.height || 0,
    })).sort((a, b) => a.pageNo - b.pageNo)
    const blocks = (d.blocks || []).map(b => ({
      id: b.id, pageNo: b.pageNo, blockType: b.blockType,
      x: b.x || 0, y: b.y || 0, w: b.w || 0, h: b.h || 0,
      sortNo: b.sortNo || 0, content: b.content || '', hasCrop: !!b.imagePath,
    }))
    result.value = {
      docId, pageCount: pages.length, blockCount: blocks.length,
      costMs: d.doc?.parseMs || 0, questionCount: 0, questions: [], savedIds: [],
      fullText: blocks.filter(b => b.blockType === 'TEXT').map(b => b.content).join('\n'),
      blocks, pages,
    }
    imported.value = false
    await loadDetail()
    docThumbVer.value++
    tabName.value = 'split'
    if (!pages.length) ElMessage.warning('该文档没有可用的页面图片')
    else ElMessage.success(`已打开 ${pages.length} 页原图，可在「切分调整」里框选区域识别`)
  } catch (e) {
    ElMessage.error('打开文档失败：' + (e?.message || e))
  }
}

async function reparse(docId) {
  try {
    const r = await http.post(`/scan/parse/${docId}?autoSave=false`)
    result.value = r
    imported.value = false
    await loadDetail()
    docThumbVer.value++
    if (r.warning) {
      tabName.value = 'split'
      ElMessage.warning('整卷 OCR 不完整，已保留原图，可框选区域识别')
    } else {
      ElMessage.success('重新解析完成：题目为草稿，确认后入库')
    }
    loadDocs()
  } catch (e) {
    ElMessage.error('重新解析失败：' + (e?.message || e))
    await openDoc(docId)   // 失败也把原图打开，不再出现「点了没反应」
  }
}

// ---------- 题目草稿：修改 / 加图 / 复制 / 拆分 / 合并 / 删除，确认后入库 ----------
function draftList() { return result.value?.questions || [] }
const imgList = p => (p || '').split(',').map(s => s.trim()).filter(Boolean)

function markDraftChanged() {
  imported.value = false
  // 草稿结构变了，之前确认入库得到的数据库 id 不再对应，避免后续按旧 id 误改题库
  draftList().forEach(q => { delete q.id })
}

function editDraft(row) {
  draftTarget.value = row
  draftEditVisible.value = true
}

function onDraftSaved(saved) {
  if (!draftTarget.value) return
  Object.assign(draftTarget.value, saved)
  draftTarget.value = null
  markDraftChanged()
  ElMessage.success('草稿已更新，确认入库后写入题库')
}

/** 给无图题补图：草稿立即显示，已确认入库的题目同步更新题库 */
async function addDraftImage(opt, row) {
  try {
    const fd = new FormData()
    fd.append('file', opt.file)
    const r = await http.post('/file/upload', fd)
    const list = imgList(row.imagePath)
    list.push(r.url)
    row.imagePath = list.join(',')
    row.hasImage = 1
    if (imported.value && row.id) {
      // 带上完整实体再保存，避免 updateById 把课序、题号等未传字段清空
      const full = await http.get(`/question/${row.id}`)
      await http.post('/question', { ...full, imagePath: row.imagePath, hasImage: 1 })
      ElMessage.success('配图已保存到题库')
    } else {
      markDraftChanged()
      ElMessage.success('配图已加入草稿，确认入库后写入题库')
    }
  } catch (e) {
    ElMessage.error('上传配图失败：' + (e?.message || e))
  }
}

function copyDraft(row) {
  const list = draftList()
  const i = list.indexOf(row)
  if (i < 0) return
  const cp = {
    ...row,
    options: (row.options || []).map(o => ({ ...o })),
    imagePath: row.imagePath || '',
    qNo: row.qNo ? row.qNo + 'c' : 'copy',
  }
  list.splice(i + 1, 0, cp)
  markDraftChanged()
}

function removeDraft(row) {
  const list = draftList()
  const i = list.indexOf(row)
  if (i < 0) return
  list.splice(i, 1)
  markDraftChanged()
}

function mergeDraft(row) {
  const list = draftList()
  const i = list.indexOf(row)
  const nx = list[i + 1]
  if (!nx) {
    ElMessage.warning('没有下一题可合并')
    return
  }
  if (row.qType !== nx.qType) {
    ElMessage.info('两题题型不同，合并后沿用第一题题型')
  }
  const imgs = [row.imagePath, nx.imagePath].filter(Boolean).join(',')
  const merged = {
    ...row,
    stem: [row.stem, nx.stem].filter(Boolean).join('\n'),
    options: [...(row.options || []), ...(nx.options || [])],
    answer: [row.answer, nx.answer].filter(s => (s || '').trim()).join('；'),
    analysis: [row.analysis, nx.analysis].filter(s => (s || '').trim()).join('\n'),
    knowledge: [row.knowledge, nx.knowledge].filter(s => (s || '').trim()).join('，'),
    score: (Number(row.score) || 5) + (Number(nx.score) || 5),
    imagePath: imgs,
    hasImage: imgs ? 1 : 0,
  }
  list.splice(i, 2, merged)
  markDraftChanged()
}

function splitDraft(row) {
  splitTarget.value = row
  const opts = (row.options || []).map(o => `${o.key}. ${o.content}`).join('\n')
  splitText.value = [row.stem, opts].filter(Boolean).join('\n')
  splitVisible.value = true
}

function doSplit() {
  const parts = splitText.value
    .split(/\n\s*-{2,}\s*\n|\n\s*\n/)
    .map(s => s.trim())
    .filter(Boolean)
  if (parts.length < 2) {
    ElMessage.warning('请用空行或 --- 把内容分成至少 2 段')
    return
  }
  const list = draftList()
  const i = list.indexOf(splitTarget.value)
  if (i < 0) return
  const src = splitTarget.value
  const made = parts.map((seg, k) => ({
    ...src,
    options: [],
    stem: seg,
    answer: k === 0 ? (src.answer || '') : '',
    analysis: k === 0 ? (src.analysis || '') : '',
    imagePath: k === 0 ? (src.imagePath || '') : '',
    hasImage: k === 0 && src.imagePath ? 1 : 0,
    qNo: src.qNo ? `${src.qNo}(${k + 1})` : `${k + 1}`,
  }))
  list.splice(i, 1, ...made)
  splitTarget.value = null
  splitVisible.value = false
  markDraftChanged()
  ElMessage.success(`已拆分为 ${made.length} 道题，可继续编辑补全选项与答案`)
}

async function confirmImport() {
  const docId = result.value?.docId
  if (!docId) return
  if (!draftList().length) {
    ElMessage.warning('没有可入库的题目')
    return
  }
  confirmLoading.value = true
  try {
    const r = await http.post(`/scan/docs/${docId}/questions/confirm`, { items: draftList() })
    // 记下入库后的题目 id：已入库状态下点「加图」可直接同步题库
    if (Array.isArray(r.ids)) draftList().forEach((q, i) => { q.id = r.ids[i] })
    imported.value = true
    ElMessage.success(`已入库 ${r.imported} 道题`)
    loadDocs()
  } catch (e) {
    ElMessage.error('入库失败：' + (e?.message || e))
  } finally {
    confirmLoading.value = false
  }
}

// ---------- 历史文档删除 / 批量删除 ----------
const docsTableRef = ref(null)
const docSel = ref([])

async function delDoc(row) {
  try {
    await ElMessageBox.confirm(`确定删除文档「${row.docName || row.id}」？删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await http.delete(`/scan/docs/${row.id}`)
    ElMessage.success('已删除')
    seenDocIds?.delete(String(row.id))
    loadDocs()
  } catch (e) { /* 拦截器已提示 */ }
}

async function delDocsBatch() {
  const ids = docSel.value.map(r => r.id)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 个文档？删除后不可恢复`, '批量删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    const r = await http.post('/scan/docs/batch-delete', { ids })
    ElMessage.success(`已删除 ${r.deleted ?? ids.length} 个文档`)
    ids.forEach(id => seenDocIds?.delete(String(id)))
    docsTableRef.value?.clearSelection()
    loadDocs()
  } catch (e) { /* 拦截器已提示 */ }
}

let docsTimer = null
let seenDocIds = null

async function loadDocs() {
  try {
    const d = await http.get('/scan/docs?page=1&size=20')
    const list = d.records || []
    // 首次记录基线，之后有新文档（如手机拍照上传）时提示并自动加入列表
    if (seenDocIds === null) {
      seenDocIds = new Set(list.map(x => x.id))
    } else {
      const fresh = list.filter(x => !seenDocIds.has(x.id))
      fresh.forEach(x => seenDocIds.add(x.id))
      if (fresh.length) ElMessage.success(`已同步 ${fresh.length} 个新上传文档，点「打开原图」即可查看并框选识别`)
    }
    docs.value = list
  } catch (e) {}
}

onMounted(() => {
  loadDocs()
  docsTimer = setInterval(loadDocs, 5000)   // 手机拍照上传后，电脑端自动出现
  http.get('/scan/health').then(d => ocrOk.value = d.ocrAvailable).catch(() => {})
})
onBeforeUnmount(() => {
  clearInterval(docsTimer)
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('keydown', onShotKeydown)
  stopStream()
})

function boxStyle(b) {
  const W = curPageSize.value.width || 1
  const H = curPageSize.value.height || 1
  return {
    left: (b.x / W * 100) + '%',
    top: (b.y / H * 100) + '%',
    width: (b.w / W * 100) + '%',
    height: (b.h / H * 100) + '%',
  }
}
</script>

<style scoped>
.layout-preview { position: relative; max-width: 640px; border: 1px solid #eee; }
.page-img { width: 100%; display: block; }
.block-box { position: absolute; border: 2px solid; }
.block-box.TEXT { border-color: #409EFF; background: rgba(64, 158, 255, .08); }
.block-box.IMAGE { border-color: #E6A23C; background: rgba(230, 162, 60, .12); }
.block-box.TABLE { border-color: #67C23A; background: rgba(103, 194, 58, .12); }
.block-tag { font-size: 10px; background: #fff; padding: 0 4px; }
.fulltext { white-space: pre-wrap; font-size: 13px; line-height: 1.7; }
.shot-canvas { width: 100%; display: block; cursor: crosshair; }
.se-empty { color: #909399; font-size: 13px; padding: 12px; }
.qgroup { margin-bottom: 16px; }
.qgroup-title { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.qgroup-no { color: #409eff; }
</style>
