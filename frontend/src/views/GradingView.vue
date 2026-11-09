<template>
  <div>
    <h2 class="page-title">扫描阅卷</h2>
    <div class="page-card">
      <el-form inline>
        <el-form-item label="选择试卷">
          <el-select v-model="paperId" style="width:260px" placeholder="选择已组好的试卷">
            <el-option v-for="p in papers" :key="p.id" :label="`${p.title}（${p.totalScore}分）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="阅卷模式">
          <el-select v-model="gradeMode" style="width:190px" @change="saveGradeMode">
            <el-option label="全部 OCR（识别手写答案）" value="ocr" />
            <el-option label="快速截图（主观题不 OCR）" value="fast" />
          </el-select>
          <span style="margin-left:10px;font-size:12px;color:#909399">两种模式均识别姓名/班级/考号</span>
        </el-form-item>
        <el-form-item>
          <el-upload :auto-upload="false" :limit="10" multiple accept="image/*"
                     :on-change="onPickFile" :on-remove="onRemoveFile">
            <el-button>选择答题卡扫描件（多页可多选）</el-button>
          </el-upload>
          <div v-if="files.length > 1" class="multi-page-hint">
            已选 {{ files.length }} 页，将按顺序逐页识别并合并为一条阅卷记录
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="grading" :disabled="!paperId || !file" @click="doGrade">
            开始阅卷
          </el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="success" :disabled="!paperId" @click="openMobile">📱 手机拍照录入</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
                title="说明：客观题（选择/判断）按涂点自动判分；主观题在明细中手工给分后确认。扫描件需包含答题卡四角的黑色定位方块。" />
    </div>

    <!-- 手机拍照录入：扫码打开手机页，提交结果实时回显到本页 -->
    <el-dialog v-model="mobileDlg" title="📱 手机拍照录入" width="440px" :close-on-click-modal="false"
               @open="startMobilePoll" @close="stopMobilePoll">
      <div class="mobile-qr-box">
        <canvas ref="qrCanvas" class="mobile-qr"></canvas>
        <el-input v-model="mobileUrl" size="small" style="margin-top:10px">
          <template #prepend>链接</template>
        </el-input>
        <div class="mobile-hint">
          用手机（与电脑同一局域网）扫码或输入链接打开拍照页，拍一张自动识别一张，
          结果实时显示在本页。手机拍照时请保证答题卡四角定位方块完整。
        </div>
        <el-alert v-if="isLocalHost" type="warning" :closable="false" show-icon style="margin-top:8px"
                  title="当前地址是 localhost，手机无法访问。请用电脑局域网 IP 打开本系统后重新生成，或把上方链接中的 localhost 改成电脑 IP。" />
        <div class="mobile-stat">已收到 {{ mobileCount }} 张答题卡，新记录会自动展示到「阅卷结果」</div>
      </div>
    </el-dialog>

    <div v-if="result" class="page-card">
      <h3 style="margin-top:0">
        阅卷结果
        <el-tag size="small" :type="result.status === 'CONFIRMED' ? 'success' : 'warning'" style="margin-left:8px">
          {{ result.status === 'CONFIRMED' ? '已确认' : '待确认' }}
        </el-tag>
        <el-tag size="small" type="info" style="margin-left:6px">
          {{ result.mode === 'fast' ? '快速截图模式' : '全部 OCR 模式' }}
        </el-tag>
      </h3>
      <el-descriptions :column="narrow ? 2 : 4" border size="small">
        <el-descriptions-item label="考号">
          <span>{{ result.studentNo || '未识别' }}</span>
          <el-popover v-if="result.idCrop" placement="bottom" width="300" trigger="hover" :show-after="80" :hide-after="120">
            <template #reference>
              <el-button size="small" text type="primary" style="padding:0 4px">看手写</el-button>
            </template>
            <el-image :src="result.idCrop" style="width:100%"
                      :preview-src-list="[result.idCrop]" preview-teleported />
          </el-popover>
          <el-popover v-if="result.infoCrop" placement="bottom" width="480" trigger="hover" :show-after="80" :hide-after="120">
            <template #reference>
              <el-button size="small" text type="primary" style="padding:0 4px">看信息区整图</el-button>
            </template>
            <el-image :src="result.infoCrop" style="width:100%"
                      :preview-src-list="[result.infoCrop]" preview-teleported />
          </el-popover>
        </el-descriptions-item>
        <el-descriptions-item label="姓名">
          <span :style="result.studentName && result.studentName !== '待确认' ? 'color:#67c23a;font-weight:600' : 'color:#909399'">
            {{ result.studentName || '未识别' }}
          </span>
          <el-popover v-if="result.nameCrop" placement="bottom" width="300" trigger="hover" :show-after="80" :hide-after="120">
            <template #reference>
              <el-button size="small" text type="primary" style="padding:0 4px">看截图</el-button>
            </template>
            <el-image :src="result.nameCrop" style="width:100%"
                      :preview-src-list="[result.nameCrop]" preview-teleported />
          </el-popover>
        </el-descriptions-item>
        <el-descriptions-item label="班级">
          <span :style="result.className ? 'color:#67c23a;font-weight:600' : 'color:#909399'">
            {{ result.className || '未识别' }}
          </span>
          <el-popover v-if="result.classCrop" placement="bottom" width="300" trigger="hover" :show-after="80" :hide-after="120">
            <template #reference>
              <el-button size="small" text type="primary" style="padding:0 4px">看截图</el-button>
            </template>
            <el-image :src="result.classCrop" style="width:100%"
                      :preview-src-list="[result.classCrop]" preview-teleported />
          </el-popover>
        </el-descriptions-item>
        <el-descriptions-item label="客观题得分">
          <span style="color:#409EFF;font-weight:700">{{ result.objectiveScore }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="总分">{{ result.totalScore }} / {{ result.fullScore }}</el-descriptions-item>
        <el-descriptions-item label="统计">
          对 {{ result.correct }} · 错 {{ result.wrong }} · 空白 {{ result.blank }} · 多涂 {{ result.multi }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 回车跳到下一题时短暂显示下一题作答情况：OCR 识别学生答案 + 完整作答区截图 -->
      <div v-if="nextHint" class="next-hint" :style="hintStyle">
        <div class="hint-head">
          <span>下一题 <b>第 {{ nextHint.qNo }} 题</b>（{{ typeLabel(nextHint.qType) }}）</span>
          <span>学生作答 <b class="hint-ans">{{ nextHint.studentAnswer || cellOcr || '—' }}</b></span>
          <span>标准答案 {{ nextHint.standardAnswer || '—' }}</span>
          <span>{{ verdictLabel(nextHint.verdict) }} · 满分 {{ nextHint.score }}</span>
        </div>
        <div class="hint-body">
          <el-image v-if="nextHint.cellCrop" :src="nextHint.cellCrop" class="hint-img" fit="contain"
                    :preview-src-list="[nextHint.cellCrop]" preview-teleported />
          <div class="hint-ocr">
            <div class="hint-ocr-title">OCR 识别的学生答案</div>
            <div class="hint-ocr-text">{{ cellOcr || (ocrLoading ? '识别中…' : result.mode === 'fast' ? '快速截图模式该题答案未做 OCR，请看截图人工判分' : '未识别出，请看完整截图') }}</div>
          </div>
        </div>
      </div>

      <el-table :data="result.details" size="small" style="margin-top:12px">
        <el-table-column prop="qNo" label="题号" width="70" />
        <el-table-column label="题型" width="100">
          <template #default="{ row }">{{ typeLabel(row.qType) }}</template>
        </el-table-column>
        <el-table-column prop="standardAnswer" label="标准答案" width="110" />
        <el-table-column label="学生作答" width="160">
          <template #default="{ row }">
            <span class="stu-ans" :title="row.studentAnswer || ''">{{ row.studentAnswer || '—' }}</span>
            <el-tag v-if="row.ocrAnswer" size="small" type="info" style="margin-left:4px">OCR</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="涂点填充率" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="(v, k) in row.fills || {}" :key="k" size="small" style="margin:2px"
                    :type="v >= 0.38 ? 'danger' : 'info'">
              {{ k }}:{{ (v * 100).toFixed(0) }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="判定" width="100">
          <template #default="{ row }">
            <el-tag :type="verdictType(row.verdict)" size="small">{{ verdictLabel(row.verdict) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="作答区截图" width="100">
          <template #default="{ row }">
            <el-popover v-if="row.cellCrop" placement="left" :width="320" trigger="hover" :show-after="80" :hide-after="120">
              <template #reference>
                <el-button size="small" text type="primary" style="padding:0">看截图</el-button>
              </template>
              <el-image :src="row.cellCrop" style="width:100%"
                        :preview-src-list="[row.cellCrop]" preview-teleported />
            </el-popover>
            <span v-else style="color:#c0c4cc;font-size:12px">无</span>
          </template>
        </el-table-column>
        <el-table-column label="得分（可修改）" width="160">
          <template #default="{ row, $index }">
            <el-input-number v-model="row.gotScore" size="small" :min="0" :max="row.score" :step="0.5"
                             style="width:120px" :ref="el => setScoreRef(el, $index)"
                             @keyup.enter="onScoreEnter($index)" />
            <span style="color:#909399;font-size:12px"> / {{ row.score }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top:12px;display:flex;gap:10px;align-items:center">
        <el-button type="success" :loading="savingScore" @click="saveScores">保存分数修改</el-button>
        <span style="font-size:12px;color:#909399">保存后自动重算客观/主观/总分，可反复修改</span>
      </div>
    </div>

    <div class="page-card">
      <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
        <h3 style="margin:0 0 4px;flex:1;min-width:200px">
          阅卷记录（结果已自动保存；班级/姓名可直接填写，点「查看 / 改分」可重新打开修改）
        </h3>
        <el-button size="small" type="warning" plain @click="goStats">📊 阅卷统计</el-button>
        <el-button size="small" type="success" :loading="exporting" @click="exportExcel">📥 导出 Excel</el-button>
        <el-button size="small" type="primary" plain :loading="exportingImgs" @click="exportImages">
          🖼 导出答题卡原图
        </el-button>
        <el-button size="small" type="danger" :disabled="!recSel.length" @click="delRecordsBatch">
          🗑 批量删除{{ recSel.length ? `（${recSel.length}）` : '' }}
        </el-button>
      </div>
      <el-table ref="recTableRef" :data="records" size="small" style="margin-top:8px"
                @selection-change="s => recSel = s">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="createTime" label="时间" width="150" class-name="col-optional">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="班级" width="145">
          <template #default="{ row }">
            <div style="display:flex;align-items:center;gap:2px">
              <el-input v-model="row.className" size="small" placeholder="填写" @change="saveInfo(row)" />
              <el-popover v-if="row.imagePath" placement="left" :width="280" trigger="hover" :show-after="80" :hide-after="120">
                <template #reference>
                  <el-button size="small" text type="primary" style="padding:0 3px">图</el-button>
                </template>
                <div style="font-size:12px;color:#909399;margin-bottom:4px">班级选区截图</div>
                <el-image :src="`/api/grading/${row.id}/crop/class`" style="width:100%"
                          :preview-src-list="[`/api/grading/${row.id}/crop/class`]" preview-teleported />
              </el-popover>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="姓名" width="135">
          <template #default="{ row }">
            <div style="display:flex;align-items:center;gap:2px">
              <el-input v-model="row.studentName" size="small" placeholder="填写" @change="saveInfo(row)" />
              <el-popover v-if="row.imagePath" placement="left" :width="280" trigger="hover" :show-after="80" :hide-after="120">
                <template #reference>
                  <el-button size="small" text type="primary" style="padding:0 3px">图</el-button>
                </template>
                <div style="font-size:12px;color:#909399;margin-bottom:4px">姓名选区截图</div>
                <el-image :src="`/api/grading/${row.id}/crop/name`" style="width:100%"
                          :preview-src-list="[`/api/grading/${row.id}/crop/name`]" preview-teleported />
              </el-popover>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="学号/考号" width="150">
          <template #default="{ row }">
            <span>{{ row.studentNo || '未识别' }}</span>
            <el-popover v-if="row.imagePath" placement="left" :width="280" trigger="hover" :show-after="80" :hide-after="120">
              <template #reference>
                <el-button size="small" text type="primary" style="padding:0 3px">图</el-button>
              </template>
              <div style="font-size:12px;color:#909399;margin-bottom:4px">考号手写选区截图</div>
              <el-image :src="`/api/grading/${row.id}/crop/id`" style="width:100%"
                        :preview-src-list="[`/api/grading/${row.id}/crop/id`]" preview-teleported />
            </el-popover>
          </template>
        </el-table-column>
        <el-table-column prop="objectiveScore" label="客观题" width="80" />
        <el-table-column prop="subjectiveScore" label="主观题" width="80" />
        <el-table-column prop="totalScore" label="总分" width="80" />
        <el-table-column prop="fullScore" label="满分" width="80" class-name="col-optional" />
        <el-table-column prop="status" label="状态" width="100" class-name="col-optional">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status === 'CONFIRMED' ? '已确认' : '自动判分' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原图" width="70">
          <template #default="{ row }">
            <el-image v-if="row.imagePath" :src="`/api/grading/${row.id}/image`"
                      :preview-src-list="[`/api/grading/${row.id}/image`]" preview-teleported fit="cover"
                      style="width:42px;height:56px;border-radius:3px;cursor:zoom-in" />
            <span v-else style="color:#c0c4cc;font-size:12px">无</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="viewRecord(row.id)">查看 / 改分</el-button>
            <el-button size="small" text type="danger" @click="delRecord(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import http, { Q_TYPE_LABELS } from '../api'

const route = useRoute(); const router = useRouter()
const papers = ref([]); const paperId = ref(null); const file = ref(null)
/** 多页答题卡：一次可选多张扫描件，按选择顺序逐页识别并合并到同一条阅卷记录 */
const files = ref([])
const onPickFile = f => {
  files.value.push(f.raw)
  file.value = f.raw // 兼容单页流程的禁用判断
}
const onRemoveFile = f => {
  files.value = files.value.filter(x => x !== f.raw)
  file.value = files.value[0] || null
}

function goStats() {
  router.push({ path: '/grading-stats', query: paperId.value ? { paperId: paperId.value } : {} })
}
const grading = ref(false); const result = ref(null); const records = ref([])
const savingScore = ref(false)
const gradeMode = ref(localStorage.getItem('examGradeMode') || 'ocr')
const saveGradeMode = () => localStorage.setItem('examGradeMode', gradeMode.value)
const narrow = ref(window.innerWidth <= 768)
const onNarrowResize = () => { narrow.value = window.innerWidth <= 768 }
const typeLabel = t => Q_TYPE_LABELS[t] || t
const verdictLabel = v => ({ CORRECT: '正确', WRONG: '错误', BLANK: '未作答', MULTI: '多涂', MANUAL: '待人工' }[v] || v)
const verdictType = v => ({ CORRECT: 'success', WRONG: 'danger', BLANK: 'info', MULTI: 'warning', MANUAL: 'warning' }[v] || 'info')

/** 得分框回车：焦点跳到下一题，并短暂显示下一题学生作答情况供打分参考 */
const scoreRefs = []
const nextHint = ref(null)
const hintStyle = ref({ left: '50%', bottom: '16px', transform: 'translateX(-50%)' })
const cellOcr = ref('')      // 下一题作答区 OCR 识别出的学生答案
const ocrLoading = ref(false)
let hintTimer = null
let hintAt = -1
let ocrSeq = 0
/** 异步识别下一题作答区；用序号丢弃过期结果，避免快速回车时串题 */
async function loadCellOcr(d) {
  const seq = ++ocrSeq
  cellOcr.value = ''
  ocrLoading.value = false
  if (!result.value?.gradeId || !d?.paperItemId || !d.cellCrop) return
  // 快速截图模式不对主观题作答区/按需提示做 OCR，只保留完整截图供人工判分（考生信息仍已 OCR）
  if (result.value.mode === 'fast') return
  // 阅卷时已 OCR 过的主观题直接复用，不重复请求
  if (d.ocrAnswer || d.studentAnswer) {
    cellOcr.value = d.ocrAnswer || d.studentAnswer
    return
  }
  ocrLoading.value = true
  try {
    const r = await http.get(`/grading/${result.value.gradeId}/cell/${d.paperItemId}/ocr`)
    if (seq === ocrSeq) cellOcr.value = r?.text || ''
  } catch (e) {
    // OCR 不可用/超时时保持空，人工仍可看完整截图
  } finally {
    if (seq === ocrSeq) ocrLoading.value = false
  }
}
function setScoreRef(el, i) {
  if (el) scoreRefs[i] = el
  else delete scoreRefs[i]
}
/** 浮动条贴着当前焦点得分框：优先放下方，空间不够放上方；窗口滚动/缩放时重算 */
function placeHint(i) {
  const box = scoreRefs[i]?.$el?.getBoundingClientRect?.()
  if (!box) {
    hintStyle.value = { left: '50%', bottom: '16px', transform: 'translateX(-50%)' }
    return
  }
  const w = Math.min(640, window.innerWidth - 24)
  const h = 290
  const left = Math.max(12, Math.min(box.left - 40, window.innerWidth - w - 12))
  let top = box.bottom + 10
  if (top + h > window.innerHeight) {
    const above = box.top - h - 10
    top = above >= 10 ? above : Math.max(10, Math.min(window.innerHeight - h - 10, box.bottom + 10))
  }
  hintStyle.value = { left: left + 'px', top: top + 'px' }
}
function repositionHint() {
  if (nextHint.value) placeHint(hintAt)
}
function onScoreEnter(i) {
  const details = result.value?.details || []
  const next = details[i + 1]
  if (!next) {
    nextHint.value = null
    ElMessage.info('已是最后一题，修改完成后点击「保存分数修改」')
    return
  }
  hintAt = i + 1
  nextHint.value = next
  placeHint(hintAt)
  loadCellOcr(next)
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => { nextHint.value = null }, 6000)   // 留足看大图与 OCR 文字的时间
  nextTick(() => {
    const comp = scoreRefs[i + 1]
    const input = comp?.$el?.querySelector?.('input')
    if (comp?.focus) { try { comp.focus() } catch (e) { /* 老版本组件无 focus */ } }
    if (input) { input.focus(); input.select() }
    placeHint(hintAt)
  })
}
watch(result, () => {
  nextHint.value = null
  cellOcr.value = ''
  ocrLoading.value = false
  ocrSeq++
  clearTimeout(hintTimer)
})

/** 打开一条已保存的阅卷记录：结果已入库，可在此修改任意题得分 */
async function viewRecord(id) {
  try {
    const d = await http.get(`/grading/${id}`)
    const g = d.grade || {}
    const cnt = { CORRECT: 0, WRONG: 0, BLANK: 0, MULTI: 0 }
    for (const x of d.details || []) cnt[x.verdict] = (cnt[x.verdict] || 0) + 1
    result.value = {
      gradeId: g.id, mode: g.mode, studentNo: g.studentNo, studentName: g.studentName, className: g.className,
      nameCrop: d.nameCrop, classCrop: d.classCrop, idCrop: d.idCrop, infoCrop: d.infoCrop,
      objectiveScore: g.objectiveScore,
      totalScore: g.totalScore, fullScore: g.fullScore, status: g.status,
      correct: cnt.CORRECT, wrong: cnt.WRONG, blank: cnt.BLANK, multi: cnt.MULTI,
      details: d.details || [],
    }
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) { /* 拦截器已提示 */ }
}

/** 保存分数修改：全量提交，后端按明细重算客观/主观/总分 */
async function saveScores() {
  if (!result.value?.gradeId) return
  savingScore.value = true
  try {
    const items = (result.value.details || [])
      .map(d => ({ paperItemId: d.paperItemId, score: Number(d.gotScore) || 0 }))
    const g = await http.post(`/grading/${result.value.gradeId}/adjust`, items)
    result.value.status = 'CONFIRMED'
    result.value.objectiveScore = g.objectiveScore
    result.value.totalScore = g.totalScore
    ElMessage.success(`分数已保存：总分 ${g.totalScore} / ${g.fullScore}`)
    loadRecords()
  } finally {
    savingScore.value = false
  }
}

// ---------- 手机拍照录入 ----------
const mobileDlg = ref(false); const mobileUrl = ref(''); const mobileCount = ref(0)
const qrCanvas = ref(null)
let mobileTimer = null
let seenIds = null              // 首次轮询只记录基线，之后的新记录才实时展示
const isLocalHost = computed(() =>
  ['localhost', '127.0.0.1'].includes(location.hostname))

function openMobile() {
  mobileUrl.value = `${location.origin}/m/grade?paperId=${paperId.value}`
  mobileDlg.value = true
}

async function renderQr() {
  await nextTick()
  qrCanvas.value && QRCode.toCanvas(qrCanvas.value, mobileUrl.value, { width: 220, margin: 1 })
}

function startMobilePoll() {
  renderQr()
  seenIds = null
  mobileCount.value = 0
  mobileTimer = setInterval(mobilePoll, 3000)
  mobilePoll()
}

function stopMobilePoll() {
  clearInterval(mobileTimer)
  mobileTimer = null
}

/** 轮询本试卷阅卷记录：手机端每提交一张，这里立刻拉详情展示 */
async function mobilePoll() {
  if (!paperId.value) return
  try {
    const list = await http.get(`/grading/list?paperId=${paperId.value}`)
    if (seenIds === null) {
      seenIds = new Set((list || []).map(r => r.id))
      mobileCount.value = seenIds.size
      return
    }
    const fresh = (list || []).filter(r => !seenIds.has(r.id)).sort((a, b) => a.id - b.id)
    for (const r of fresh) {
      seenIds.add(r.id)
      mobileCount.value = seenIds.size
      await showMobileResult(r.id)
    }
    loadRecords()
  } catch (e) {}
}

/** 拉取新阅卷记录详情（手机提交或后台轮询发现），直接复用页面上的「阅卷结果」区展示 */
async function showMobileResult(id) {
  try {
    const d = await http.get(`/grading/${id}`)
    const g = d.grade || {}
    const cnt = { CORRECT: 0, WRONG: 0, BLANK: 0, MULTI: 0 }
    for (const x of d.details || []) cnt[x.verdict] = (cnt[x.verdict] || 0) + 1
    result.value = {
      gradeId: g.id, mode: g.mode, studentNo: g.studentNo, studentName: g.studentName, className: g.className,
      nameCrop: d.nameCrop, classCrop: d.classCrop, idCrop: d.idCrop, infoCrop: d.infoCrop,
      objectiveScore: g.objectiveScore, status: g.status,
      totalScore: g.totalScore, fullScore: g.fullScore,
      correct: cnt.CORRECT, wrong: cnt.WRONG, blank: cnt.BLANK, multi: cnt.MULTI,
      details: d.details || [],
    }
    ElMessage.success(`📱 新答题卡已出分：考号 ${g.studentNo || '未识别'}，总分 ${g.totalScore} / ${g.fullScore}`)
  } catch (e) {}
}

async function doGrade() {
  grading.value = true
  try {
    // 多页答题卡：按选择顺序逐页识别；第 2 页起传 mergeInto，合并到同一条阅卷记录。
    // 后端会读取每页顶部的机器可读页码标识自动判定页码，无需手动指定顺序。
    const list = files.value.length ? files.value : [file.value]
    let merged = null
    for (const f of list) {
      if (!f) continue
      const fd = new FormData()
      fd.append('file', f)
      let url = `/grading/grade?paperId=${paperId.value}&mode=${gradeMode.value}`
      if (merged?.gradeId != null) url += `&mergeInto=${merged.gradeId}`
      merged = await http.post(url, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    }
    result.value = merged
    ElMessage.success(list.length > 1 ? `阅卷完成（共 ${list.length} 页，${merged.details?.length ?? 0} 题）` : '阅卷完成')
    // 本机刚出的分不再由轮询重复弹出
    if (result.value?.gradeId != null && seenRecordIds) {
      seenRecordIds.add(String(result.value.gradeId))
    }
    loadRecords()
  } finally { grading.value = false }
}

async function confirmScore() {
  const manual = (result.value.details || []).filter(d => d.verdict === 'MANUAL')
    .map(d => ({ paperItemId: d.paperItemId, score: d.gotScore }))
  await http.post(`/grading/${result.value.gradeId}/adjust`, manual)
  ElMessage.success('分数已确认')
  loadRecords()
}

// ---------- 阅卷记录删除 / 批量删除 ----------
const recTableRef = ref(null)
const recSel = ref([])

async function delRecord(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除记录 #${row.id}（考号 ${row.studentNo || '未识别'}）？原图与选区截图将一并删除`,
      '删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await http.delete(`/grading/${row.id}`)
    ElMessage.success('已删除')
    seenRecordIds?.delete(String(row.id))
    loadRecords()
  } catch (e) { /* 拦截器已提示 */ }
}

async function delRecordsBatch() {
  const ids = recSel.value.map(r => r.id)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条阅卷记录？原图与选区截图将一并删除`, '批量删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    const r = await http.post('/grading/batch-delete', { ids })
    ElMessage.success(`已删除 ${r.deleted ?? ids.length} 条记录`)
    ids.forEach(id => seenRecordIds?.delete(String(id)))
    recTableRef.value?.clearSelection()
    loadRecords()
  } catch (e) { /* 拦截器已提示 */ }
}

let seenRecordIds = null   // 已展示过的记录，用于发现新记录（含手机提交）后自动弹出结果

async function loadRecords() {
  const list = await http.get('/grading/list').catch(() => [])
  records.value = list || []
  // 首次只记基线；之后每发现一条新记录就自动展示到「阅卷结果」区
  if (seenRecordIds === null) {
    seenRecordIds = new Set((list || []).map(r => String(r.id)))
    return
  }
  const fresh = (list || [])
    .filter(r => !seenRecordIds.has(String(r.id)))
    .sort((a, b) => Number(a.id) - Number(b.id))
  for (const r of fresh) {
    seenRecordIds.add(String(r.id))
    await showMobileResult(r.id)
  }
}

/** 补录班级/姓名（输入框失焦/回车即保存） */
async function saveInfo(row) {
  try {
    await http.post(`/grading/${row.id}/info`, { className: row.className || '', studentName: row.studentName || '' })
    ElMessage.success('班级/姓名已保存')
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------- 导出 ----------
const exporting = ref(false)
const exportingImgs = ref(false)

async function exportExcel() {
  exporting.value = true
  try {
    const XLSX = await import('xlsx')
    const rows = (records.value || []).map((r, i) => [
      i + 1, (r.createTime || '').replace('T', ' ').slice(0, 19),
      r.className || '', r.studentName || '', r.studentNo || '',
      Number(r.objectiveScore) || 0, Number(r.subjectiveScore) || 0,
      Number(r.totalScore) || 0, Number(r.fullScore) || 0,
      r.status === 'CONFIRMED' ? '已确认' : '自动判分',
    ])
    const ws = XLSX.utils.aoa_to_sheet([[
      '序号', '时间', '班级', '姓名', '学号/考号', '客观题', '主观题', '总分', '满分', '状态',
    ], ...rows])
    ws['!cols'] = [{ wch: 5 }, { wch: 20 }, { wch: 10 }, { wch: 10 }, { wch: 12 },
                   { wch: 8 }, { wch: 8 }, { wch: 8 }, { wch: 8 }, { wch: 10 }]
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '阅卷记录')
    const d = new Date(); const p = n => String(n).padStart(2, '0')
    XLSX.writeFile(wb, `阅卷记录-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.xlsx`)
  } finally {
    exporting.value = false
  }
}

async function exportImages() {
  exportingImgs.value = true
  try {
    const JSZip = (await import('jszip')).default
    const zip = new JSZip()
    let n = 0
    for (const r of records.value || []) {
      try {
        const res = await fetch(`/api/grading/${r.id}/image`)
        if (!res.ok) continue
        const blob = await res.blob()
        const name = [r.className, r.studentName && r.studentName !== '待确认' ? r.studentName : '',
                      r.studentNo || `ID${r.id}`].filter(Boolean).join('-')
        zip.file(`${name}.jpg`, blob)
        n++
      } catch (e) { /* 单张失败跳过 */ }
    }
    if (!n) { ElMessage.warning('没有可导出的答题卡原图（旧记录未保存原图）'); return }
    const blob = await zip.generateAsync({ type: 'blob' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `答题卡原图-${n}张.zip`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${n} 张答题卡原图`)
  } finally {
    exportingImgs.value = false
  }
}

let recTimer = null

onMounted(async () => {
  papers.value = await http.get('/paper/list').catch(() => [])
  if (route.query.paperId) paperId.value = Number(route.query.paperId)
  loadRecords()
  window.addEventListener('resize', onNarrowResize)
  window.addEventListener('scroll', repositionHint, true)   // 捕获所有滚动容器，提示条跟随焦点行
  window.addEventListener('resize', repositionHint)
  // 阅卷记录常驻自动刷新：手机拍照提交后，即使二维码弹窗已关闭也能看到新记录
  recTimer = setInterval(() => { if (!mobileDlg.value) loadRecords() }, 5000)
})

onBeforeUnmount(() => {
  stopMobilePoll()
  clearInterval(recTimer)
  clearTimeout(hintTimer)
  window.removeEventListener('resize', onNarrowResize)
  window.removeEventListener('scroll', repositionHint, true)
  window.removeEventListener('resize', repositionHint)
})
</script>

<style scoped>
.mobile-qr-box { text-align: center; }
.mobile-qr { margin: 0 auto; display: block; }
.mobile-hint { font-size: 12px; color: #909399; line-height: 1.7; margin-top: 10px; text-align: left; }
.mobile-stat { margin-top: 10px; font-size: 13px; color: #67c23a; font-weight: 600; }
/* 回车跳到下一题时短暂出现的作答参考条：fixed 跟随焦点行，滚动窗口也始终可见 */
.next-hint {
  position: fixed; z-index: 3000; width: fit-content; max-width: calc(100vw - 24px);
  max-height: calc(100vh - 20px); overflow: auto;
  display: flex; flex-direction: column; gap: 8px;
  padding: 8px 12px; font-size: 13px; color: #409eff;
  background: #ecf5ff; border: 1px solid #b3d8ff; border-radius: 6px;
  box-shadow: 0 6px 18px rgba(64, 158, 255, .25);
}
.hint-head { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.stu-ans { display: inline-block; max-width: 118px; vertical-align: middle; font-size: 12px; line-height: 1.4; white-space: pre-wrap; word-break: break-all; }
.next-hint .hint-ans { font-size: 17px; color: #303133; }
.hint-body { display: flex; gap: 12px; align-items: flex-start; }
/* 完整显示作答区截图（不裁切，按原比例缩放），点击可放大 */
.hint-img {
  width: 360px; max-width: 60vw; max-height: 260px; min-height: 80px;
  border: 1px solid #d9ecff; border-radius: 4px; background: #fff; cursor: zoom-in;
}
.hint-ocr { width: 210px; max-height: 260px; overflow: auto; background: #fff; border-radius: 4px; padding: 6px 8px; }
.hint-ocr-title { font-weight: 600; color: #303133; margin-bottom: 4px; }
.hint-ocr-text { color: #606266; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
</style>
