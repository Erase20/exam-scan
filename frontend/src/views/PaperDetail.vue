<template>
  <div v-if="paper">
    <h2 v-if="!preview" class="page-title">{{ paper.title }}
      <el-tag style="margin-left:8px">总分 {{ paper.totalScore }} 分 · {{ paper.questionCount }} 题</el-tag>
      <el-button size="small" text type="primary" style="margin-left:10px" @click="renameTitle">✎ 修改名称</el-button>
    </h2>
    <div v-if="!preview" class="page-card">
      <el-button type="primary" @click="$router.push('/papers')">返回列表</el-button>
      <el-button type="warning" plain @click="startPreview">试卷预览</el-button>
      <el-dropdown style="margin-left:12px" @command="m => exportPaper(m)">
        <el-button>
          导出 Word ▾
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="none">试卷（不含答案）</el-dropdown-item>
            <el-dropdown-item command="inline">试卷 + 答案填在相应位置</el-dropdown-item>
            <el-dropdown-item command="separate">试卷 + 答案页（末页集中）</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button type="success" @click="genSheet" :loading="genLoading">生成答题卡</el-button>
      <el-button type="primary" plain @click="openAddPicker">＋ 从题库添加题目</el-button>
    </div>

    <!-- ==================== 试卷格式预览（可显隐答案、可直接打印） ==================== -->
    <template v-else>
      <div class="preview-bar no-print">
        <el-button @click="preview = false">← 返回编辑</el-button>
        <span style="font-weight:600">试卷预览</span>
        <el-switch v-model="showAnswer" active-text="显示答案" inactive-text="隐藏答案" />
        <span style="font-size:12px;color:#909399">显示答案时：填空题答案逐空填入空位，选择/判断填入括号，其余题型答案标注在题后</span>
        <el-button type="primary" style="margin-left:auto" @click="printPaper">打印 / 另存 PDF</el-button>
      </div>
      <div class="paper-sheet">
        <h1 class="p-title">{{ paper.title }}</h1>
        <p class="p-sub">{{ paper.subject || '' }}（满分：{{ fmtNum(paper.totalScore) }}分　考试时间：{{ paper.duration }}分钟）</p>
        <p class="p-info">班级：____________　姓名：____________　学号：____________　得分：________</p>
        <div v-for="(sec, si) in previewSections" :key="si" class="p-section">
          <h3 class="p-sec-head">{{ cnNum(si) }}、{{ sec.name }}（共 {{ sec.questions.length }} 小题，{{ sec.total }}分）</h3>
          <div v-for="(q, qi) in sec.questions" :key="q.key" class="p-q">
            <p class="p-stem"><b>{{ qi + 1 }}．</b><span v-html="q.stemHtml"></span></p>
            <div v-if="q.images.length" class="p-imgs">
              <img v-for="(u, i) in q.images" :key="i" :src="u" alt="">
            </div>
            <p v-if="q.opts.length" class="p-opts">
              <span v-for="o in q.opts" :key="o.key">{{ o.key }}．{{ o.content }}　　</span>
            </p>
            <p v-if="q.ansHtml" class="p-extra" v-html="q.ansHtml"></p>
          </div>
        </div>
      </div>
    </template>

    <template v-if="!preview">
    <div v-for="(items, secNo) in sections" :key="secNo" class="page-card">
      <h3 style="margin-top:0">{{ items[0].item.sectionName }}
        <span style="color:#909399; font-size:13px">（共 {{ items.length }} 题）</span>
      </h3>
      <div v-for="(row, idx) in items" :key="row.item.id" class="q-row">
        <div class="q-head">
          <b>{{ idx + 1 }}.</b>
          <el-tag size="small" style="margin:0 8px">{{ typeLabel(row.item.qType) }}</el-tag>
          <el-input-number v-model="row.item.score" size="small" :min="0.5" :max="50" :step="0.5"
                           style="width:100px" @change="s => updateScore(row.item, s)" />
          <span style="font-size:12px;color:#909399">分</span>
          <el-button size="small" text type="primary" style="margin-left:8px"
                     @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text type="warning" @click="openReplacePicker(row)">替换</el-button>
          <el-button size="small" text title="上移"
                     :disabled="idx === 0"
                     @click="moveItem(row.item, 'up')">↑</el-button>
          <el-button size="small" text title="下移"
                     :disabled="idx === items.length - 1"
                     @click="moveItem(row.item, 'down')">↓</el-button>
          <el-popconfirm title="从试卷中移除该题？" @confirm="removeItem(row.item.id)">
            <template #reference><el-button size="small" type="danger" text style="margin-left:12px">移除</el-button></template>
          </el-popconfirm>
        </div>
        <div class="q-stem">{{ row.question?.stem }}</div>
        <div v-if="imgList(row.question?.imagePath).length" class="q-imgs">
          <el-image v-for="(u, i) in imgList(row.question.imagePath)" :key="i" :src="u"
                    :preview-src-list="imgList(row.question.imagePath)" :initial-index="i" preview-teleported
                    fit="contain"
                    style="max-width:260px;max-height:220px;margin:6px 8px 0 0;border:1px solid #eee;border-radius:4px" />
        </div>
        <div v-if="opts(row).length" class="q-opts">
          <span v-for="o in opts(row)" :key="o.key" style="margin-right:16px">{{ o.key }}. {{ o.content }}</span>
        </div>
        <details v-if="row.question?.answer" class="q-ans">
          <summary>答案与解析</summary>
          <div>答：{{ row.question.answer }}</div>
          <div v-if="row.question.analysis">解析：{{ row.question.analysis }}</div>
        </details>
      </div>
    </div>
    </template>

    <QuestionEditDialog v-model="editVisible" :question="editQuestion" @saved="onQuestionSaved" />
    <QuestionPickerDialog v-model="pickerVisible"
                          :title="pickerTarget ? '替换试卷中的这道题' : '从题库添加题目'"
                          :multiple="!pickerTarget" :exclude-ids="existingIds" @confirm="onPicked" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'
import QuestionEditDialog from '../components/QuestionEditDialog.vue'
import QuestionPickerDialog from '../components/QuestionPickerDialog.vue'

const route = useRoute()
const paper = ref(null); const items = ref([]); const genLoading = ref(false)
const editVisible = ref(false); const editQuestion = ref(null)
const pickerVisible = ref(false); const pickerTarget = ref(null)  // null=添加题目，row=替换该题
// ---------- 试卷预览 ----------
const preview = ref(false)
const showAnswer = ref(false)
const CN_NUMS = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']
const cnNum = i => CN_NUMS[i] || String(i + 1)
const fmtNum = n => String(Number(n ?? 0)).replace(/\.0$/, '')
const escHtml = s => (s == null ? '' : String(s))
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
// 与后端 PaperAnswerUtil 同一套规则：括号空位 / 连续下划线
const BLANK_RE = /[（(][\s\u3000_＿]*[)）]|[_＿]{2,}/g
const countBlanks = s => ((s || '').match(BLANK_RE) || []).length
const splitAnswers = a => (a || '').split(/[\s；;，,]+/).map(s => s.trim()).filter(Boolean)
const canFill = t => t === 'FILL' || t === 'CHOICE' || t === 'JUDGE'

const previewSections = computed(() => Object.entries(sections.value).map(([secNo, rows]) => {
  const questions = rows.map(row => {
    const q = row.question || {}
    const answers = showAnswer.value ? splitAnswers(q.answer) : []
    let stem = escHtml(q.stem || '').trim()
    let leftover = []
    if (answers.length && canFill(q.qType)) {
      let i = 0
      stem = stem.replace(BLANK_RE, m => i < answers.length
        ? `<span class="blank-ans">（${escHtml(answers[i++])}）</span>` : m)
      leftover = answers.slice(i)
    }
    const parts = []
    if (showAnswer.value) {
      // 答案已全部填入空位时不再重复；未填入的（含解答/计算类）标注在题后
      const filled = canFill(q.qType) && answers.length && leftover.length === 0 && countBlanks(q.stem) > 0
      if (answers.length && !filled) {
        const ansText = canFill(q.qType) ? leftover.join('；') : (q.answer || '')
        if (ansText) parts.push('【答案】' + escHtml(ansText))
      }
      if (q.analysis) parts.push((parts.length ? '　' : '') + '【解析】' + escHtml(q.analysis))
    }
    return {
      key: row.item.id,
      stemHtml: stem,
      ansHtml: parts.join(''),
      images: imgList(q.imagePath),
      opts: opts(row),
    }
  })
  const total = rows.reduce((s, r) => s + Number(r.item.score || 0), 0)
  return { name: rows[0].item.sectionName, questions, total: fmtNum(total) }
}))

function startPreview() { showAnswer.value = false; preview.value = true }
function printPaper() { window.print() }
const existingIds = computed(() => items.value.map(r => r.item.questionId))
const sections = computed(() => {
  const m = {}
  for (const r of items.value) {
    (m[r.item.sectionNo] = m[r.item.sectionNo] || []).push(r)
  }
  return m
})
const typeLabel = t => Q_TYPE_LABELS[t] || t
const imgList = p => (p || '').split(',').map(s => s.trim()).filter(Boolean)
const opts = row => {
  try { return JSON.parse(row.question?.options || '[]') } catch (e) { return [] }
}

async function load() {
  const d = await http.get(`/paper/${route.params.id}`)
  paper.value = d.paper
  items.value = d.items
}

async function renameTitle() {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的试卷名称', '修改试卷名称', {
      inputValue: paper.value.title,
      inputPlaceholder: '试卷名称',
      inputValidator: v => (v && v.trim() && v.trim().length <= 100) ? true : '名称不能为空且不超过 100 字',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    const p = await http.post(`/paper/${paper.value.id}/title?title=${encodeURIComponent(value.trim())}`)
    paper.value.title = p?.title || value.trim()
    ElMessage.success('试卷名称已修改')
  } catch (e) { /* 取消或接口错误由拦截器提示 */ }
}

async function updateScore(item, score) {
  await http.post(`/paper/item/${item.id}/score?score=${score}`)
  ElMessage.success('分值已更新')
  await load()
}

async function removeItem(id) {
  await http.delete(`/paper/item/${id}`)
  load()
}

async function moveItem(item, direction) {
  try {
    await http.post(`/paper/item/${item.id}/move?direction=${direction}`)
    await load()
  } catch (e) { /* 错误提示由拦截器统一弹出 */ }
}

function openEdit(row) {
  editQuestion.value = row.question
  editVisible.value = true
}

async function onQuestionSaved() {
  await load()
}

function openAddPicker() {
  pickerTarget.value = null
  pickerVisible.value = true
}

function openReplacePicker(row) {
  pickerTarget.value = row
  pickerVisible.value = true
}

async function onPicked(questions) {
  if (!questions.length) return
  try {
    if (pickerTarget.value) {
      await http.post(`/paper/item/${pickerTarget.value.item.id}/replace?questionId=${questions[0].id}`)
      ElMessage.success('题目已替换，如需同步答题卡请点「生成答题卡」')
    } else {
      for (const q of questions) {
        await http.post(`/paper/${paper.value.id}/item?questionId=${q.id}`)
      }
      ElMessage.success(`已添加 ${questions.length} 道题，如需同步答题卡请点「生成答题卡」`)
    }
    await load()
  } catch (e) { /* 错误提示由拦截器统一弹出 */ }
}

async function genSheet() {
  genLoading.value = true
  try {
    const s = await http.post(`/answer-sheet/generate?paperId=${paper.value.id}`)
    ElMessage.success('答题卡已生成，去「答题卡」页面查看')
  } finally { genLoading.value = false }
}

async function exportPaper(mode = 'none') {
  const tag = { inline: '_含答案', separate: '_含答案(答案页)' }[mode] || ''
  const resp = await fetch(`/api/paper/${paper.value.id}/export?answerMode=${mode}`)
  const blob = await resp.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = paper.value.title + tag + '.docx'
  a.click()
}

onMounted(load)
</script>

<style scoped>
.q-row { padding: 10px 0; border-bottom: 1px dashed #eee; }
.q-stem { margin: 6px 0 4px; line-height: 1.7; }
.q-opts { color: #555; font-size: 13px; }
.q-ans { margin-top: 6px; font-size: 13px; color: #67C23A; }

/* ---------- 试卷预览 ---------- */
.preview-bar { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
.paper-sheet {
  max-width: 820px; margin: 0 auto; background: #fff; color: #111;
  padding: 44px 56px; box-shadow: 0 2px 12px rgba(0,0,0,.1);
  font-family: '宋体', SimSun, 'STSong', serif; font-size: 14px;
}
.p-title { text-align: center; font-size: 22px; font-weight: 700; margin: 0 0 10px; }
.p-sub, .p-info { text-align: center; font-size: 13px; margin: 4px 0; color: #222; }
.p-sec-head { font-size: 15px; font-weight: 700; margin: 20px 0 8px; }
.p-q { margin-bottom: 12px; }
.p-stem { line-height: 1.9; margin: 6px 0 2px; }
.p-opts { color: #333; margin: 2px 0 2px 24px; }
.p-imgs img { max-width: 300px; max-height: 240px; margin: 6px 8px 0 0; border: 1px solid #eee; }
.p-extra { color: #c00000; font-size: 13px; margin: 2px 0 0 24px; }
.blank-ans { color: #c00000; font-weight: 600; }

@media print {
  .no-print { display: none !important; }
  .paper-sheet { box-shadow: none; margin: 0; max-width: none; padding: 0; }
}
</style>
