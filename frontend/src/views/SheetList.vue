<template>
  <div>
    <h2 class="page-title">答题卡管理</h2>
    <div class="page-card">
      <el-table :data="sheets" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="paperId" label="试卷ID" width="90" />
        <el-table-column prop="pageCount" label="页数" width="70" />
        <el-table-column prop="markerCount" label="定位点" width="80" />
        <el-table-column prop="createTime" label="生成时间" width="160" />
        <el-table-column label="操作" width="510">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="preview(row.id)">预览</el-button>
            <el-button size="small" type="warning" plain @click="regenerate(row)">重新排版</el-button>
            <el-button size="small" type="success" @click="openAnswers(row)">设置答案</el-button>
            <el-button size="small" @click="printSheet(row)">打印</el-button>
            <el-button size="small" type="primary" plain @click="downloadPdf(row)">下载PDF</el-button>
            <el-popconfirm title="删除该答题卡？" @confirm="del(row.id)">
              <template #reference><el-button size="small" type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dlg" :title="curTitle" width="70%">
      <img v-if="previewUrl" :src="previewUrl" style="width:100%" />
    </el-dialog>

    <!-- 快速设置答案：选择/判断点按钮，其余文本录入，保存写入题库 -->
    <el-dialog v-model="answerDlg" :title="answerTitle" width="880px" append-to-body
               :close-on-click-modal="false">
      <div v-loading="answerLoading">
        <el-alert type="info" :closable="false" show-icon
                  title="保存后答案会写入题库，机器阅卷和「导出含答案」都以这里的标准答案为准。" />

        <div class="ans-toolbar">
          <el-button size="small" @click="batchOpen = !batchOpen">批量录入</el-button>
          <el-button size="small" @click="resetFromBank">恢复题库答案</el-button>
          <span class="ans-stat">已填 {{ answeredCount }} / {{ answerItems.length }}</span>
        </div>

        <div v-if="batchOpen" class="batch-box">
          <el-input v-model="batchText" type="textarea" :rows="4"
                    placeholder="每行一题：1 A、2 B；也支持 1-5 ABCDA 连续填充。判断题用 √ / ×。" />
          <div class="batch-foot">
            <el-button size="small" type="primary" @click="applyBatch">解析并填入</el-button>
            <span class="batch-hint">题号是左侧灰色数字（卷面连续序号）</span>
          </div>
        </div>

        <div class="ans-scroll">
          <el-empty v-if="!answerItems.length" description="该答题卡没有题目" :image-size="60" />
          <div v-for="g in answerGroups" :key="g.sectionNo" class="ans-group">
            <div class="ans-group-title">{{ g.sectionName }}（{{ g.items.length }} 题）</div>
            <div v-for="it in g.items" :key="it.questionId" class="ans-row">
              <div class="ans-line">
                <span class="ans-seq">{{ it.seq }}</span>
                <el-tag size="small" :type="it.qType === 'CHOICE' || it.qType === 'JUDGE' ? 'warning' : 'info'">
                  {{ typeLabel(it.qType) }}
                </el-tag>
                <span class="ans-stem" :title="it.stem">{{ it.stem }}</span>

                <el-radio-group v-if="it.qType === 'CHOICE'" v-model="it.answer" size="small">
                  <el-radio-button v-for="k in choiceKeys(it)" :key="k" :value="k">{{ k }}</el-radio-button>
                  <el-radio-button value="">空</el-radio-button>
                </el-radio-group>
                <el-radio-group v-else-if="it.qType === 'JUDGE'" v-model="it.answer" size="small">
                  <el-radio-button value="√">√ 对</el-radio-button>
                  <el-radio-button value="×">× 错</el-radio-button>
                  <el-radio-button value="">空</el-radio-button>
                </el-radio-group>
                <el-input v-else v-model="it.answer" size="small" style="width:240px" placeholder="参考答案（可选）" />
              </div>
              <!-- 选择题：展示题库里的选项内容，选中项高亮 -->
              <div v-if="it.qType === 'CHOICE' && it.opts.length" class="ans-opts">
                <span v-for="o in it.opts" :key="o.key" class="ans-opt"
                      :class="{ active: it.answer === (o.key || '').trim().toUpperCase() }">
                  {{ o.key }}. {{ o.content }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="answerDlg = false">取消</el-button>
        <el-button type="primary" :loading="answerSaving" @click="saveAnswers">保存并写入题库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'

const sheets = ref([]); const dlg = ref(false); const previewUrl = ref(''); const curTitle = ref('')

const answerDlg = ref(false); const answerLoading = ref(false); const answerSaving = ref(false)
const answerSheet = ref(null); const answerItems = ref([])
const batchOpen = ref(false); const batchText = ref('')

const typeLabel = t => Q_TYPE_LABELS[t] || t || '其他'
const answerTitle = computed(() => `快速设置答案 · ${answerSheet.value?.title || ''}`)
const answeredCount = computed(() => answerItems.value.filter(x => (x.answer || '').trim()).length)

const answerGroups = computed(() => {
  const m = new Map()
  for (const it of answerItems.value) {
    const key = it.sectionNo == null ? 0 : it.sectionNo
    if (!m.has(key)) {
      m.set(key, { sectionNo: key, sectionName: it.sectionName || '未命名大题', items: [] })
    }
    m.get(key).items.push(it)
  }
  return [...m.values()]
})

async function load() {
  const d = await http.get('/answer-sheet/list?page=1&size=50')
  sheets.value = d.records || []
}

function preview(id) {
  // 加时间戳，重新排版后打开预览不会命中浏览器里的旧图缓存
  previewUrl.value = `/api/answer-sheet/${id}/image?scale=2&t=${Date.now()}`
  curTitle.value = '答题卡预览'
  dlg.value = true
}

/* 按最新规则重排已有答题卡：解答大题至少 4 条横线，标题与标准答案保留 */
async function regenerate(row) {
  try {
    await ElMessageBox.confirm(
      '将按最新规则重新排版该答题卡（每道解答大题至少 4 条横线），标题与已设置的标准答案会保留。',
      '重新排版', { type: 'warning', confirmButtonText: '重新排版', cancelButtonText: '取消' })
  } catch { return }
  const s = await http.post(`/answer-sheet/${row.id}/regenerate`)
  row.pageCount = s?.pageCount || row.pageCount
  ElMessage.success('已重新排版，请重新预览或打印')
  await load()
}

/* 下载按 A4 分页的多页 PDF：每页答题卡对应一张 A4，直接打印不串页 */
function downloadPdf(row) {
  const a = document.createElement('a')
  a.href = `/api/answer-sheet/${row.id}/pdf`
  a.download = row.title + '.pdf'
  a.click()
}

/* 按 A4 逐页打印：每页单独出图，避免整张长图被压成一页 */
function printSheet(row) {
  const pages = Math.max(1, row.pageCount || 1)
  let imgs = ''
  for (let i = 0; i < pages; i++) {
    imgs += '<div class="pg"><img src="/api/answer-sheet/' + row.id + '/page/' + i +
      '/image?scale=1" onload="if(++n>=' + pages + ')window.print()"></div>'
  }
  const html = '<!doctype html><html><head><meta charset="utf-8"><title>' + row.title + '</title>' +
    '<style>@page{size:A4;margin:0}html,body{margin:0;padding:0}img{width:100%;display:block}' +
    '.pg{page-break-after:always;break-after:page}.pg:last-child{page-break-after:auto;break-after:auto}</style>' +
    '</head><body><script>var n=0;<\/script>' + imgs + '</body></html>'
  const w = window.open('', '_blank')
  if (!w) { ElMessage.warning('浏览器拦截了打印窗口，请允许弹窗后重试'); return }
  w.document.open()
  w.document.write(html)
  w.document.close()
}

/* 判断题题库答案可能是 √/×/对/错/A/B，统一成答题卡按钮的值 */
function normalizeJudge(raw) {
  const v = (raw || '').trim()
  if (!v) return ''
  const u = v.toUpperCase()
  if (v === '×' || u === 'X' || v.includes('错') || v.includes('不正确')
      || ['F', 'FALSE', 'B'].includes(u)) return '×'
  if (v === '√' || v.includes('对') || v.includes('正确')
      || ['T', 'TRUE', 'A'].includes(u)) return '√'
  return v
}

/* 选择题答案统一成单个大写选项字母，避免「b.」之类的录入导致按钮选不中 */
function normalizeChoice(raw) {
  const v = (raw || '').trim().toUpperCase()
  const m = v.match(/[A-D]/)
  return m ? m[0] : (raw || '').trim()
}

/* 题库 options 是 JSON 字符串：[{key,content}] 或 {A:'xx'} 都要能解析 */
function parseOpts(raw) {
  let arr = raw
  if (typeof raw === 'string') {
    try { arr = JSON.parse(raw || '[]') } catch (e) { return [] }
  }
  if (Array.isArray(arr)) return arr.map(o => ({ key: o?.key || '', content: o?.content || '' }))
  if (arr && typeof arr === 'object') return Object.entries(arr).map(([k, v]) => ({ key: k, content: String(v) }))
  return []
}

/* 选择题按钮按题目实际选项生成；没有选项时退回 A-D */
function choiceKeys(it) {
  const ks = (it.opts || []).map(o => (o.key || '').trim().toUpperCase()).filter(Boolean)
  return ks.length ? ks : ['A', 'B', 'C', 'D']
}

async function openAnswers(row) {
  answerSheet.value = row
  answerDlg.value = true
  answerLoading.value = true
  batchOpen.value = false
  batchText.value = ''
  try {
    const d = await http.get(`/answer-sheet/${row.id}/answers`)
    answerItems.value = (d.items || []).map(x => {
      let answer = x.answer || ''
      if (x.qType === 'JUDGE') answer = normalizeJudge(answer)
      else if (x.qType === 'CHOICE') answer = normalizeChoice(answer)
      return { ...x, answer, bankAnswer: answer, opts: parseOpts(x.options) }
    })
  } catch (e) {
    answerItems.value = []
  } finally {
    answerLoading.value = false
  }
}

function resetFromBank() {
  for (const it of answerItems.value) it.answer = it.bankAnswer
  ElMessage.success('已恢复为题库现有答案')
}

function applyBatch() {
  const lines = batchText.value.split(/\r?\n/).map(s => s.trim()).filter(Boolean)
  let n = 0
  for (const line of lines) {
    const range = line.match(/^(\d+)\s*[-~]\s*(\d+)\s*[:：]?\s*([A-Da-d√×]+)$/)
    if (range) {
      const start = Number(range[1]); const end = Number(range[2]); const ans = range[3]
      for (let i = start; i <= end && i - start < ans.length; i++) {
        if (setBySeq(i, ans[i - start])) n++
      }
      continue
    }
    const one = line.match(/^(\d+)\s*[.、:：]?\s*(\S+)$/)
    if (one && setBySeq(Number(one[1]), one[2])) n++
  }
  ElMessage.success(n ? `已填入 ${n} 题，请核对后保存` : '没有识别到可填充的题号')
}

function setBySeq(seq, raw) {
  const it = answerItems.value.find(x => Number(x.seq) === seq)
  if (!it) return false
  const v = String(raw || '').trim()
  if (!v) return false
  if (it.qType === 'CHOICE') {
    it.answer = v.toUpperCase().slice(0, 1)
  } else if (it.qType === 'JUDGE') {
    it.answer = normalizeJudge(v)
  } else {
    it.answer = v
  }
  return true
}

async function saveAnswers() {
  if (!answerItems.value.length) return
  const changed = answerItems.value.filter(
    x => (x.answer || '').trim() !== (x.bankAnswer || '').trim()
  )
  if (!changed.length) {
    ElMessage.info('答案没有变化')
    return
  }
  answerSaving.value = true
  try {
    const r = await http.post(`/answer-sheet/${answerSheet.value.id}/answers`, {
      items: changed.map(x => ({ questionId: x.questionId, answer: (x.answer || '').trim() })),
    })
    ElMessage.success(`已保存 ${r.updated} 题答案，并写入题库`)
    answerDlg.value = false
  } finally {
    answerSaving.value = false
  }
}

async function del(id) {
  await http.delete(`/answer-sheet/${id}`)
  load()
}

onMounted(load)
</script>

<style scoped>
.ans-toolbar { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
.ans-stat { margin-left: auto; font-size: 12px; color: #909399; }
.batch-box { margin-bottom: 10px; }
.batch-foot { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.batch-hint { font-size: 12px; color: #909399; }
.ans-scroll { max-height: 52vh; overflow-y: auto; padding-right: 6px; }
.ans-group { margin-bottom: 10px; }
.ans-group-title { font-weight: 600; font-size: 13px; margin: 8px 0 4px; color: #303133; }
.ans-row { padding: 5px 0; border-bottom: 1px dashed #f0f0f0; }
.ans-line { display: flex; align-items: center; gap: 8px; }
.ans-opts { display: flex; flex-wrap: wrap; gap: 4px 14px; padding: 3px 0 2px 38px; }
.ans-opt { font-size: 12px; color: #606266; }
.ans-opt.active { color: #409eff; font-weight: 600; }
.ans-seq { width: 30px; text-align: center; color: #909399; font-size: 12px; flex-shrink: 0; }
.ans-stem {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; font-size: 12px; color: #606266;
}
</style>
