<template>
  <div>
    <h2 class="page-title">智能组卷</h2>

    <el-tabs v-model="tab" type="border-card" class="build-tabs">
      <!-- ==================== 自动组卷 ==================== -->
      <el-tab-pane label="🤖 自动组卷" name="auto">
        <div class="page-card" style="border:none;box-shadow:none;padding:10px 0">
          <el-form inline>
            <el-form-item label="试卷标题"><el-input v-model="cfg.title" style="width:240px" placeholder="如：五年级数学期末测试卷" /></el-form-item>
            <el-form-item label="学科"><el-select v-model="cfg.subject" style="width:110px"><el-option v-for="s in subjects" :key="s" :label="s" :value="s" /></el-select></el-form-item>
            <el-form-item label="年级"><el-select v-model="cfg.grade" style="width:110px"><el-option v-for="g in grades" :key="g" :label="g" :value="g" /></el-select></el-form-item>
            <el-form-item label="时长(分)"><el-input-number v-model="cfg.duration" :min="20" :max="180" /></el-form-item>
            <el-form-item label="目标总分"><el-input-number v-model="cfg.targetScore" :min="0" :max="1000" /></el-form-item>
          </el-form>

          <h4>大题配置（按题型设置题量与分值，系统自动选题）</h4>
          <el-table :data="cfg.sections" size="small">
            <el-table-column label="题型" width="160">
              <template #default="{ row }">
                <el-select v-model="row.qType" size="small">
                  <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="大题名称" width="180">
              <template #default="{ row }"><el-input v-model="row.sectionName" size="small" placeholder="默认用题型名" /></template>
            </el-table-column>
            <el-table-column label="题量" width="130">
              <template #default="{ row }"><el-input-number v-model="row.count" size="small" :min="0" :max="50" /></template>
            </el-table-column>
            <el-table-column label="每题分值" width="130">
              <template #default="{ row }"><el-input-number v-model="row.scorePerQuestion" size="small" :min="0.5" :max="50" :step="0.5" /></template>
            </el-table-column>
            <el-table-column label="小计">
              <template #default="{ row }">{{ ((row.count || 0) * (row.scorePerQuestion || 0)).toFixed(1) }} 分</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ $index }">
                <el-button size="small" type="danger" text @click="cfg.sections.splice($index, 1)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top:10px">
            <el-button size="small" @click="addSection">+ 添加大题</el-button>
            <span style="margin-left:20px; font-weight:600">预估总分：{{ total }}</span>
            <el-button type="primary" style="margin-left:24px" :loading="building" @click="build">开始组卷</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 自定义组卷 ==================== -->
      <el-tab-pane label="✍️ 自定义组卷" name="custom">
        <div class="page-card" style="border:none;box-shadow:none;padding:10px 0">
          <el-form inline>
            <el-form-item label="试卷标题"><el-input v-model="cTitle" style="width:240px" placeholder="如：五年级数学专题练习" /></el-form-item>
            <el-form-item label="学科"><el-select v-model="cSubject" style="width:110px" clearable><el-option v-for="s in subjects" :key="s" :label="s" :value="s" /></el-select></el-form-item>
            <el-form-item label="年级"><el-select v-model="cGrade" style="width:110px" clearable><el-option v-for="g in grades" :key="g" :label="g" :value="g" /></el-select></el-form-item>
            <el-form-item label="时长(分)"><el-input-number v-model="cDuration" :min="20" :max="180" /></el-form-item>
          </el-form>
        </div>

        <el-row :gutter="16">
          <!-- 左：题库筛选 -->
          <el-col :span="14">
            <div class="page-card">
              <h4 style="margin-top:0">① 从题库筛选试题</h4>
              <el-form inline size="small">
                <el-form-item label="关键词"><el-input v-model="cq.keyword" style="width:180px" clearable placeholder="题干关键词" @keyup.enter="loadPool" /></el-form-item>
                <el-form-item label="学科">
                  <el-select v-model="cq.subject" style="width:110px" clearable placeholder="全部" @change="loadPool">
                    <el-option v-for="s in subjects" :key="s" :label="s" :value="s" />
                  </el-select>
                </el-form-item>
                <el-form-item label="年级">
                  <el-select v-model="cq.grade" style="width:110px" clearable placeholder="全部" @change="loadPool">
                    <el-option v-for="g in grades" :key="g" :label="g" :value="g" />
                  </el-select>
                </el-form-item>
                <el-form-item label="题型">
                  <el-select v-model="cq.qType" style="width:110px" clearable placeholder="全部">
                    <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
                  </el-select>
                </el-form-item>
                <el-form-item label="难度">
                  <el-select v-model="cq.difficulty" style="width:90px" clearable placeholder="全部">
                    <el-option v-for="n in 5" :key="n" :label="n + ' ★'" :value="n" />
                  </el-select>
                </el-form-item>
                <el-form-item label="知识点"><el-input v-model="cq.knowledge" style="width:120px" clearable /></el-form-item>
                <el-form-item label="课号">
                  <el-select v-model="cq.courseNo" multiple clearable collapse-tags collapse-tags-tooltip
                             placeholder="可单选/多选" style="width:150px">
                    <el-option v-for="n in courseNoOpts" :key="n" :label="n" :value="n" />
                  </el-select>
                  <el-checkbox v-model="cq.courseNoEmpty" style="margin-left:8px">仅看未设置</el-checkbox>
                </el-form-item>
                <el-form-item label="配图">
                  <el-checkbox v-model="cq.hasImageOnly">仅看有图</el-checkbox>
                </el-form-item>
                <el-form-item><el-button type="primary" size="small" @click="loadPool">查询</el-button></el-form-item>
              </el-form>
              <el-table :data="pool" size="small" v-loading="poolLoading" max-height="420"
                        @selection-change="onPoolSelect" ref="poolTable">
                <el-table-column type="selection" width="42" :selectable="row => !pickedIds.has(row.id)" />
                <el-table-column prop="id" label="ID" width="52" />
                <el-table-column label="题型" width="84">
                  <template #default="{ row }"><el-tag size="small">{{ typeLabel(row.qType) }}</el-tag></template>
                </el-table-column>
                <el-table-column prop="stem" label="题干" show-overflow-tooltip />
                <el-table-column label="配图" width="64" align="center">
                  <template #default="{ row }">
                    <el-image v-if="row.hasImage === 1 && row.imagePath"
                              :src="imgList(row.imagePath)[0]" :preview-src-list="imgList(row.imagePath)"
                              preview-teleported fit="cover"
                              style="width:36px;height:36px;border-radius:4px;border:1px solid #eee" />
                    <span v-else style="color:#c0c4cc">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="难度" width="56">
                  <template #default="{ row }">{{ row.difficulty }}★</template>
                </el-table-column>
                <el-table-column label="课号" width="70">
                  <template #default="{ row }">{{ row.courseNo || '—' }}</template>
                </el-table-column>
              </el-table>
              <el-pagination style="margin-top:8px" layout="total, prev, pager, next" :total="poolTotal"
                             :page-size="cq.size" v-model:current-page="cq.page" @current-change="loadPool" />
              <div style="margin-top:8px">
                <el-button size="small" type="primary" :disabled="!poolSelection.length" @click="addPicked">
                  加入试卷（已勾选 {{ poolSelection.length }} 题）
                </el-button>
              </div>
            </div>
          </el-col>

          <!-- 右：已选题目 -->
          <el-col :span="10">
            <div class="page-card">
              <h4 style="margin-top:0;display:flex;align-items:center;gap:10px">
                ② 已选题目（{{ picked.length }} 题，共 {{ cTotal }} 分）
                <el-popconfirm :title="`移除勾选的 ${pickedSelCount} 题？`" width="200" @confirm="removePickedBatch">
                  <template #reference>
                    <el-button size="small" type="danger" text :disabled="!pickedSelCount">
                      批量移除{{ pickedSelCount ? `（${pickedSelCount}）` : '' }}
                    </el-button>
                  </template>
                </el-popconfirm>
              </h4>
              <el-empty v-if="!picked.length" description="尚未选题，请从左侧勾选加入" :image-size="60" />
              <div v-for="(p, i) in picked" :key="p.id" class="picked-row">
                <div class="picked-head">
                  <el-checkbox v-model="p.checked" style="margin-right:4px" />
                  <el-tag size="small">{{ typeLabel(p.qType) }}</el-tag>
                  <el-image v-if="p.hasImage === 1 && p.imagePath"
                            :src="imgList(p.imagePath)[0]" :preview-src-list="imgList(p.imagePath)"
                            preview-teleported fit="cover"
                            style="width:34px;height:34px;flex-shrink:0;border-radius:4px;border:1px solid #eee" />
                  <span style="flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px">
                    {{ p.stem }}
                  </span>
                  <el-button size="small" text type="primary" @click="openEdit(p)">编辑</el-button>
                  <el-input-number v-model="p.score" size="small" :min="0.5" :max="50" :step="0.5" style="width:92px" />
                  <span style="font-size:12px;color:#909399">分</span>
                  <el-button size="small" text type="danger" @click="picked.splice(i, 1)">✕</el-button>
                </div>
              </div>
              <div v-if="picked.length" style="margin-top:12px">
                <el-button size="small" @click="sortByType">按题型整理</el-button>
                <el-button size="small" type="success" :loading="cBuilding" @click="customBuild">③ 生成试卷</el-button>
                <div style="margin-top:8px;font-size:12px;color:#909399">
                  生成时同一题型会集中为一个大题，大题顺序为：选择 → 填空 → 判断 → 计算 → 解答 → 作图 → 实验
                </div>
              </div>
            </div>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>

    <div class="page-card" style="margin-top:16px">
      <div style="display:flex;align-items:center;gap:12px;margin-bottom:10px">
        <h3 style="margin:0">已组试卷</h3>
        <el-popconfirm
          :title="`确认删除选中的 ${paperSel.length} 份试卷？试卷明细将一并删除`"
          width="260"
          @confirm="delPapersBatch"
        >
          <template #reference>
            <el-button type="danger" size="small" :disabled="!paperSel.length" :loading="paperDelLoading">
              批量删除{{ paperSel.length ? `（${paperSel.length}）` : '' }}
            </el-button>
          </template>
        </el-popconfirm>
      </div>
      <el-table :data="papers" size="small" @selection-change="s => (paperSel = s)">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="标题">
          <template #default="{ row }">
            <el-button text type="primary" title="点击修改试卷名称" @click="renameTitle(row)">
              {{ row.title }} ✎
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="学科" width="90" />
        <el-table-column prop="grade" label="年级" width="90" />
        <el-table-column prop="questionCount" label="题数" width="70" />
        <el-table-column prop="totalScore" label="总分" width="70" />
        <el-table-column prop="paperType" label="方式" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.paperType === 'AUTO' ? 'warning' : 'success'">
              {{ row.paperType === 'AUTO' ? '自动' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="duration" label="时长" width="70" />
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="$router.push(`/papers/${row.id}`)">查看/调整</el-button>
            <el-dropdown style="margin-left:12px" @command="m => exportPaper(row.id, m)">
              <el-button size="small">导出 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="none">试卷（不含答案）</el-dropdown-item>
                  <el-dropdown-item command="inline">试卷 + 答案填在相应位置</el-dropdown-item>
                  <el-dropdown-item command="separate">试卷 + 答案页（末页集中）</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-popconfirm title="删除该试卷？" @confirm="delPaper(row.id)">
              <template #reference><el-button size="small" type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <QuestionEditDialog v-model="editVisible" :question="editQuestion" @saved="onQuestionSaved" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'
import QuestionEditDialog from '../components/QuestionEditDialog.vue'

const tab = ref('auto')
const subjects = ['数学', '语文', '英语', '物理', '化学', '科学']
const grades = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '七年级', '八年级', '九年级']
const typeLabel = t => Q_TYPE_LABELS[t] || t
const imgList = p => (p || '').split(',').map(s => s.trim()).filter(Boolean)

// ---------- 自动组卷 ----------
const cfg = ref({
  title: '', subject: '科学', grade: '六年级', duration: 90, targetScore: 100,
  sections: [
    { qType: 'CHOICE', sectionName: '单项选择题', count: 5, scorePerQuestion: 4 },
    { qType: 'FILL', sectionName: '填空题', count: 5, scorePerQuestion: 3 },
    { qType: 'CALC', sectionName: '计算题', count: 3, scorePerQuestion: 8 },
    { qType: 'ANSWER', sectionName: '解答题', count: 3, scorePerQuestion: 9 },
  ],
})
const papers = ref([]); const building = ref(false)
const total = computed(() => cfg.value.sections.reduce((s, x) => s + (x.count || 0) * (x.scorePerQuestion || 0), 0).toFixed(1))

function addSection() {
  cfg.value.sections.push({ qType: 'CHOICE', sectionName: '', count: 5, scorePerQuestion: 3 })
}

async function build() {
  building.value = true
  try {
    const r = await http.post('/paper/auto-build', { ...cfg.value, targetScore: cfg.value.targetScore || null })
    let msg = `组卷成功：${r.questionCount} 题，总分 ${r.totalScore} 分`
    if (r.warnings?.length) msg += '；' + r.warnings.join('；')
    r.warnings?.length ? ElMessage.warning(msg) : ElMessage.success(msg)
    loadPapers()
  } finally { building.value = false }
}

// ---------- 自定义组卷 ----------
const cTitle = ref(''); const cSubject = ref('科学'); const cGrade = ref('六年级'); const cDuration = ref(90)
const cq = reactive({ page: 1, size: 8, keyword: '', qType: '', difficulty: null, knowledge: '', subject: '', grade: '', courseNo: [], courseNoEmpty: false, hasImageOnly: false })
const courseNoOpts = ref([])
async function loadCourseNos() {
  courseNoOpts.value = await http.get('/question/course-nos').catch(() => [])
}
const pool = ref([]); const poolTotal = ref(0); const poolLoading = ref(false)
const poolSelection = ref([]); const poolTable = ref(null)
const picked = ref([]); const cBuilding = ref(false)
const editVisible = ref(false); const editQuestion = ref(null)
const pickedIds = computed(() => new Set(picked.value.map(p => p.id)))
const cTotal = computed(() => picked.value.reduce((s, p) => s + (p.score || 0), 0).toFixed(1))
const pickedSelCount = computed(() => picked.value.filter(p => p.checked).length)
function removePickedBatch() {
  for (let i = picked.value.length - 1; i >= 0; i--) {
    if (picked.value[i].checked) picked.value.splice(i, 1)
  }
  ElMessage.success('已移除勾选题目')
}

async function loadPool() {
  poolLoading.value = true
  try {
    const { hasImageOnly, ...rest } = cq
    const d = await http.post('/question/search', { ...rest, hasImage: hasImageOnly ? 1 : null })
    pool.value = d.records || []
    poolTotal.value = Number(d.total || 0)
  } finally { poolLoading.value = false }
}

function onPoolSelect(sel) { poolSelection.value = sel }

function addPicked() {
  for (const q of poolSelection.value) {
    if (!pickedIds.value.has(q.id)) {
      picked.value.push({ id: q.id, qType: q.qType, stem: q.stem, score: Number(q.score) || 5,
                          checked: false, hasImage: q.hasImage || 0, imagePath: q.imagePath || '' })
    }
  }
  poolSelection.value = []
  poolTable.value?.clearSelection()
  ElMessage.success('已加入试卷')
}

// 按题型集中排序，顺序与后端大题顺序一致（组内保持加入先后）
const TYPE_ORDER = Object.keys(Q_TYPE_LABELS)
function sortByType() {
  picked.value.sort((a, b) => TYPE_ORDER.indexOf(a.qType) - TYPE_ORDER.indexOf(b.qType))
  ElMessage.success('已按题型集中排序')
}

async function customBuild() {
  if (!picked.value.length) {
    ElMessage.warning('请先选择题目')
    return
  }
  cBuilding.value = true
  try {
    const r = await http.post('/paper/custom-build', {
      title: cTitle.value, subject: cSubject.value, grade: cGrade.value, duration: cDuration.value,
      items: picked.value.map(p => ({ questionId: p.id, score: p.score })),
    })
    let msg = `自定义组卷成功：${r.questionCount} 题 / ${r.sectionCount} 个大题，总分 ${r.totalScore} 分`
    if (r.warnings?.length) msg += '；' + r.warnings.join('；')
    r.warnings?.length ? ElMessage.warning(msg) : ElMessage.success(msg)
    picked.value = []
    loadPapers()
  } finally { cBuilding.value = false }
}

// ---------- 单题编辑 ----------
async function openEdit(p) {
  try {
    // 已选列表只保留摘要，编辑需要完整题目（选项、答案、解析、配图）
    editQuestion.value = await http.get('/question/' + p.id)
    editVisible.value = true
  } catch (e) { /* 错误提示由拦截器统一弹出 */ }
}

function onQuestionSaved(saved) {
  const p = picked.value.find(x => x.id === saved.id)
  if (p) {
    const changedType = p.qType !== saved.qType
    p.qType = saved.qType
    p.stem = saved.stem
    p.hasImage = saved.hasImage || 0
    p.imagePath = saved.imagePath || ''
    if (changedType) ElMessage.info('题型已变更，生成试卷时会按新题型归入对应大题')
  }
  const row = pool.value.find(x => x.id === saved.id)
  if (row) {
    row.qType = saved.qType
    row.stem = saved.stem
    row.hasImage = saved.hasImage || 0
    row.imagePath = saved.imagePath || ''
  }
}

// ---------- 公共 ----------
const paperSel = ref([]); const paperDelLoading = ref(false)
async function delPapersBatch() {
  paperDelLoading.value = true
  try {
    const n = await http.post('/paper/batch-delete', paperSel.value.map(p => p.id))
    ElMessage.success(`已删除 ${n} 份试卷`)
    paperSel.value = []
    loadPapers()
  } finally { paperDelLoading.value = false }
}

async function loadPapers() {
  papers.value = await http.get('/paper/list').catch(() => [])
}

async function renameTitle(row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的试卷名称', '修改试卷名称', {
      inputValue: row.title,
      inputPlaceholder: '试卷名称',
      inputValidator: v => (v && v.trim() && v.trim().length <= 100) ? true : '名称不能为空且不超过 100 字',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    const p = await http.post(`/paper/${row.id}/title?title=${encodeURIComponent(value.trim())}`)
    row.title = p?.title || value.trim()
    ElMessage.success('试卷名称已修改')
  } catch (e) { /* 取消或接口错误由拦截器提示 */ }
}

async function exportPaper(id, mode = 'none') {
  const resp = await fetch(`/api/paper/${id}/export?answerMode=${mode}`)
  const blob = await resp.blob()
  const cd = resp.headers.get('Content-Disposition') || ''
  let name = '试卷.docx'
  const m = cd.match(/filename\*=UTF-8''(.+)/)
  if (m) name = decodeURIComponent(m[1])
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
}

async function delPaper(id) {
  await http.delete(`/paper/${id}`)
  loadPapers()
}

onMounted(() => { loadPapers(); loadPool(); loadCourseNos() })
</script>

<style scoped>
.build-tabs :deep(.el-tabs__content) { padding: 12px; }
.picked-row { padding: 7px 0; border-bottom: 1px dashed #eee; }
.picked-head { display: flex; align-items: center; gap: 8px; }
</style>
