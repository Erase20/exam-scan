<template>
  <div v-if="paper">
    <h2 class="page-title">{{ paper.title }}
      <el-tag style="margin-left:8px">总分 {{ paper.totalScore }} 分 · {{ paper.questionCount }} 题</el-tag>
      <el-button size="small" text type="primary" style="margin-left:10px" @click="renameTitle">✎ 修改名称</el-button>
    </h2>
    <div class="page-card">
      <el-button type="primary" @click="$router.push('/papers')">返回列表</el-button>
      <el-button @click="exportPaper(false)">导出试卷 Word</el-button>
      <el-button @click="exportPaper(true)">导出含答案 Word</el-button>
      <el-button type="success" @click="genSheet" :loading="genLoading">生成答题卡</el-button>
      <el-button type="primary" plain @click="openAddPicker">＋ 从题库添加题目</el-button>
    </div>

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

async function exportPaper(withAnswer) {
  const resp = await fetch(`/api/paper/${paper.value.id}/export?withAnswer=${withAnswer}`)
  const blob = await resp.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = paper.value.title + (withAnswer ? '_含答案' : '') + '.docx'
  a.click()
}

onMounted(load)
</script>

<style scoped>
.q-row { padding: 10px 0; border-bottom: 1px dashed #eee; }
.q-stem { margin: 6px 0 4px; line-height: 1.7; }
.q-opts { color: #555; font-size: 13px; }
.q-ans { margin-top: 6px; font-size: 13px; color: #67C23A; }
</style>
