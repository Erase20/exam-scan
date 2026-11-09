<template>
  <div class="import-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>📥 文档导入题库</span>
          <span class="sub">支持 Word（.docx/.doc）、Excel（.xlsx/.xls）、PDF，按科目/年级批量录入试题</span>
        </div>
      </template>

      <el-form :inline="true">
        <el-form-item label="科目" required>
          <el-select v-model="form.subject" placeholder="选择科目" style="width: 140px">
            <el-option v-for="s in SUBJECTS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="年级" required>
          <el-select v-model="form.grade" placeholder="选择年级" style="width: 140px">
            <el-option v-for="g in GRADES" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="题型">
          <el-select v-model="form.qType" style="width: 140px">
            <el-option label="自动判定" value="AUTO" />
            <el-option v-for="(label, code) in Q_TYPE_LABELS" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        :on-change="onFileChange"
        :on-remove="() => (file = null)"
        accept=".docx,.doc,.xlsx,.xls,.pdf"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文档到此处，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            Word/PDF：按试卷格式切分题目（大题标题 + 题号 + 选项 + 答案解析）；
            Excel：支持"题干/题型/选项A-D/答案/解析/难度/分值"表头的结构化表格
          </div>
        </template>
      </el-upload>

      <div style="margin-top: 16px; display:flex; gap:10px; align-items:center; flex-wrap:wrap">
        <el-button type="primary" :loading="loading" :disabled="!file" @click="doImport">
          解析文档
        </el-button>
        <el-button @click="downloadTemplate">📄 下载导入模板</el-button>
        <span style="font-size:12px;color:#909399">
          先生成可编辑草稿，核对后点「确认入库」才写入题库；Excel 请按模板表头填写（题干 / 题型 / 选项A-D / 答案 / 解析 / 难度 / 分值 / 知识点）
        </span>
      </div>
    </el-card>

    <el-card v-if="result" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>导入预览：{{ result.fileName }}</span>
          <span class="sub">解析出 {{ result.questionCount }} 道题 · 当前 {{ drafts.length }} 道 ·
            {{ confirmed ? `已入库 ${savedCount} 道` : '尚未入库' }}</span>
        </div>
      </template>

      <el-alert v-if="!confirmed" type="warning" :closable="false" show-icon style="margin-bottom:10px"
                title="以下为解析草稿，还没有写入题库；可直接在表格里修改题号、题型、题干、答案、难度、分值，或点「编辑」补全选项/解析/配图，核对后点「确认入库」。" />
      <el-alert v-else type="success" :closable="false" show-icon style="margin-bottom:10px"
                :title="`已入库 ${savedCount} 道题，草稿已锁定；如需修改请到题库中编辑。`" />
      <el-alert v-for="(w, i) in result.warnings" :key="i" :title="w" type="warning"
                :closable="false" style="margin-bottom: 8px" />

      <div class="draft-toolbar">
        <el-button type="primary" :loading="confirming" :disabled="confirmed || !drafts.length"
                   @click="confirmImport">确认入库（{{ drafts.length }} 题）</el-button>
        <el-button :disabled="confirmed || !drafts.length" @click="clearDrafts">清空草稿</el-button>
        <el-button v-if="confirmed" type="success" @click="$router.push('/questions')">前往题库查看</el-button>
        <span class="draft-meta">科目 {{ form.subject }} · 年级 {{ form.grade }}（表格内单独指定的学科/年级优先）</span>
      </div>

      <el-table :data="drafts" border max-height="520" size="small">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column label="题号" width="90">
          <template #default="{ row }">
            <el-input v-model="row.qNo" size="small" :disabled="confirmed" />
          </template>
        </el-table-column>
        <el-table-column label="题型" width="130">
          <template #default="{ row }">
            <el-select v-model="row.qType" size="small" :disabled="confirmed">
              <el-option v-for="(label, code) in Q_TYPE_LABELS" :key="code" :label="label" :value="code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="题干" min-width="230">
          <template #default="{ row }">
            <el-input v-model="row.stem" type="textarea" size="small"
                      :autosize="{ minRows: 1, maxRows: 4 }" :disabled="confirmed" />
          </template>
        </el-table-column>
        <el-table-column label="选项" width="70">
          <template #default="{ row }">{{ (row.options || []).length || '-' }}</template>
        </el-table-column>
        <el-table-column label="答案" width="120">
          <template #default="{ row }">
            <el-input v-model="row.answer" size="small" :disabled="confirmed" />
          </template>
        </el-table-column>
        <el-table-column prop="analysis" label="解析" width="150" show-overflow-tooltip />
        <el-table-column label="难度" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.difficulty" :min="1" :max="5" size="small"
                             controls-position="right" style="width:80px" :disabled="confirmed" />
          </template>
        </el-table-column>
        <el-table-column label="分值" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.score" :min="0" :precision="1" size="small"
                             controls-position="right" style="width:80px" :disabled="confirmed" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row, $index }">
            <el-button size="small" text type="primary" :disabled="confirmed" @click="editDraft(row)">编辑</el-button>
            <el-button size="small" text :disabled="confirmed" @click="copyDraft(row)">复制</el-button>
            <el-button size="small" text type="danger" :disabled="confirmed" @click="removeDraft(row)">删除</el-button>
            <el-button size="small" text :disabled="confirmed || $index === 0" @click="moveDraft(row, -1)">↑</el-button>
            <el-button size="small" text :disabled="confirmed || $index === drafts.length - 1" @click="moveDraft(row, 1)">↓</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <QuestionEditDialog v-model="editVisible" :question="editTarget" draft @saved="onDraftSaved" />
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import http, { Q_TYPE_LABELS } from '../api'
import QuestionEditDialog from '../components/QuestionEditDialog.vue'

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物', '科学', '历史', '地理', '道德与法治']
const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '七年级', '八年级', '九年级', '高一', '高二', '高三']

const form = reactive({ subject: '科学', grade: '六年级', qType: 'AUTO' })
const file = ref(null)
const loading = ref(false)
const result = ref(null)
const drafts = ref([])
const confirmed = ref(false)
const savedCount = ref(0)
const confirming = ref(false)
const editVisible = ref(false)
const editTarget = ref(null)

function resetDraft() {
  result.value = null
  drafts.value = []
  confirmed.value = false
  savedCount.value = 0
}

function onFileChange(f) {
  file.value = f.raw
  resetDraft()
}

async function downloadTemplate() {
  const resp = await fetch('/api/import/template')
  const blob = await resp.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '题库导入模板.xlsx'
  a.click()
  URL.revokeObjectURL(a.href)
}

/** 只解析，生成可编辑草稿，不写题库 */
async function doImport() {
  if (!form.subject) return ElMessage.warning('请选择科目')
  if (!form.grade) return ElMessage.warning('请选择年级')
  const fd = new FormData()
  fd.append('file', file.value)
  fd.append('subject', form.subject)
  fd.append('grade', form.grade)
  fd.append('qType', form.qType)
  loading.value = true
  try {
    const r = await http.post('/import/document', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    result.value = r
    drafts.value = (r.questions || []).map(q => ({
      ...q,
      options: Array.isArray(q.options) ? q.options.map(o => ({ ...o })) : [],
    }))
    confirmed.value = false
    savedCount.value = 0
    ElMessage.success(`解析出 ${drafts.value.length} 道题，请核对后点「确认入库」`)
  } finally {
    loading.value = false
  }
}

function editDraft(row) {
  editTarget.value = row
  editVisible.value = true
}

function onDraftSaved(saved) {
  if (!editTarget.value) return
  Object.assign(editTarget.value, saved)
  editTarget.value = null
}

function copyDraft(row) {
  const i = drafts.value.indexOf(row)
  if (i < 0) return
  drafts.value.splice(i + 1, 0, {
    ...row,
    options: (row.options || []).map(o => ({ ...o })),
    qNo: row.qNo ? `${row.qNo}c` : 'copy',
  })
}

function removeDraft(row) {
  const i = drafts.value.indexOf(row)
  if (i >= 0) drafts.value.splice(i, 1)
}

function moveDraft(row, delta) {
  const i = drafts.value.indexOf(row)
  const j = i + delta
  if (i < 0 || j < 0 || j >= drafts.value.length) return
  const item = drafts.value.splice(i, 1)[0]
  drafts.value.splice(j, 0, item)
}

async function clearDrafts() {
  try {
    await ElMessageBox.confirm('确定清空当前草稿？清空后可重新解析文档', '清空确认', { type: 'warning' })
  } catch (e) { return }
  drafts.value = []
  ElMessage.success('草稿已清空')
}

/** 确认后才把编辑后的草稿写入题库 */
async function confirmImport() {
  if (!drafts.value.length) return ElMessage.warning('没有可入库的题目')
  const bad = drafts.value.filter(q => !(q.stem || '').trim())
  if (bad.length) return ElMessage.warning(`有 ${bad.length} 道题题干为空，请补全或删除`)
  confirming.value = true
  try {
    const r = await http.post('/import/confirm', {
      subject: form.subject,
      grade: form.grade,
      items: drafts.value,
    })
    confirmed.value = true
    savedCount.value = r.imported ?? drafts.value.length
    ElMessage.success(`已入库 ${savedCount.value} 道题`)
  } finally {
    confirming.value = false
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-header .sub {
  font-size: 12px;
  color: #909399;
}
.draft-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.draft-meta {
  font-size: 12px;
  color: #909399;
}
</style>
