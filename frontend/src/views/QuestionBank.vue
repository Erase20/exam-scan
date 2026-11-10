<template>
  <div>
    <h2 class="page-title">题库搜题</h2>
    <div class="page-card">
      <el-form inline>
        <el-form-item label="关键词">
          <el-input v-model="q.keyword" placeholder="输入题干关键词，多个用空格分隔" style="width:260px" clearable @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="题型">
          <el-select v-model="q.qType" style="width:130px" clearable>
            <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="学科">
          <el-select v-model="q.subject" style="width:110px" clearable>
            <el-option v-for="s in ['数学','语文','英语','物理','化学','科学']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="q.difficulty" style="width:100px" clearable placeholder="全部">
            <el-option v-for="n in 5" :key="n" :label="n + ' ★'" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="课号">
          <el-select v-model="q.courseNo" multiple clearable collapse-tags collapse-tags-tooltip
                     placeholder="可单选/多选" style="width:200px">
            <el-option v-for="n in courseNoOpts" :key="n" :label="n" :value="n" />
          </el-select>
          <el-checkbox v-model="q.courseNoEmpty" style="margin-left:8px">仅看未设置</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">搜索</el-button>
          <el-button type="success" @click="startCreate">+ 手动新增题目</el-button>
          <el-button @click="simText = !simText">相似题搜题</el-button>
          <el-popconfirm
            :title="`确认删除选中的 ${selection.length} 道题？删除后将从题库移除`"
            width="240"
            @confirm="delBatch"
          >
            <template #reference>
              <el-button type="danger" :disabled="!selection.length" :loading="delLoading">
                批量删除{{ selection.length ? `（${selection.length}）` : '' }}
              </el-button>
            </template>
          </el-popconfirm>
          <el-button @click="exportBank(true)" :loading="expLoading">导出选中</el-button>
          <el-button type="primary" plain @click="exportBank(false)" :loading="expLoading">导出全部</el-button>
          <el-button type="danger" plain @click="openClear">清空题库</el-button>
        </el-form-item>
      </el-form>
      <el-input v-if="simText" v-model="simInput" type="textarea" :rows="2"
                placeholder="粘贴一段题目文本，系统按语义相似度找出最接近的题目" style="margin-bottom:12px" />
      <el-button v-if="simText" type="success" @click="doSim" :loading="simLoading">按文本搜相似题</el-button>
    </div>

    <div class="page-card" v-if="simResults.length">
      <h3 style="margin-top:0">相似题结果</h3>
      <el-table :data="simResults" size="small">
        <el-table-column label="相似度" width="90">
          <template #default="{ row }"><el-tag type="success" size="small">{{ row.similarity }}%</el-tag></template>
        </el-table-column>
        <el-table-column label="题型" width="90">
          <template #default="{ row }">{{ typeLabel(row.question.qType) }}</template>
        </el-table-column>
        <el-table-column label="题干" show-overflow-tooltip>
          <template #default="{ row }">{{ row.question.stem }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card">
      <el-table :data="rows" v-loading="loading" size="small" @row-click="openDetail"
                @selection-change="s => (selection = s)" @sort-change="onSortChange"
                :default-sort="{ prop: '', order: null }">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="qType" label="题型" width="100" sortable="custom">
          <template #default="{ row }"><el-tag size="small">{{ typeLabel(row.qType) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="stem" label="题干" show-overflow-tooltip />
        <el-table-column label="配图" width="70" align="center">
          <template #default="{ row }">
            <el-image v-if="row.hasImage === 1 && row.imagePath"
                      :src="imgList(row.imagePath)[0]" :preview-src-list="imgList(row.imagePath)"
                      preview-teleported fit="cover"
                      style="width:40px;height:40px;border-radius:4px;border:1px solid #eee" />
            <span v-else style="color:#c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'MANUAL' ? 'warning' : 'info'" effect="plain">
              {{ row.source === 'MANUAL' ? '手动' : (row.source === 'IMPORT' ? '导入' : '扫描') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="学科" width="80" sortable="custom" />
        <el-table-column prop="grade" label="年级" width="90" sortable="custom" />
        <el-table-column prop="courseNo" label="课号" width="130" sortable="custom">
          <template #default="{ row }">
            <el-input v-model="row.courseNo" size="small" placeholder="课号"
                      @click.stop @change="saveCourseNo(row)" />
          </template>
        </el-table-column>
        <el-table-column label="难度" width="90">
          <template #default="{ row }"><el-rate :model-value="row.difficulty" disabled size="small" /></template>
        </el-table-column>
        <el-table-column prop="score" label="分值" width="70" />
        <el-table-column prop="knowledge" label="知识点" width="160" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="createTime" label="入库时间" width="145" sortable="custom">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" type="warning" @click.stop="startEdit(row)">编辑</el-button>
            <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
            <el-button size="small" type="primary" @click.stop="findSim(row)">相似题</el-button>
            <el-popconfirm title="确认删除该题？删除后将从题库移除" @confirm="del(row.id)">
              <template #reference><el-button size="small" type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:12px" layout="total, prev, pager, next" :total="total"
                     :page-size="q.size" v-model:current-page="q.page" @current-change="load" />
    </div>

    <!-- 清空题库确认 -->
    <el-dialog v-model="clearDlg" title="清空题库" width="460px" :close-on-click-modal="false" append-to-body>
      <div v-if="clearInfo">
        <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px"
                  title="清空后将物理删除题目记录，无法恢复，请谨慎操作。" />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="题库总数">{{ clearInfo.total }} 道</el-descriptions-item>
          <el-descriptions-item label="扫描识别入库">{{ clearInfo.scanCount }} 道</el-descriptions-item>
          <el-descriptions-item label="手动/导入">{{ clearInfo.otherCount }} 道</el-descriptions-item>
          <el-descriptions-item label="试卷引用条目">{{ clearInfo.paperItemCount }} 条</el-descriptions-item>
        </el-descriptions>
        <div style="margin:12px 0 6px">清空范围</div>
        <el-radio-group v-model="clearScope">
          <el-radio value="all">全部题目（{{ clearInfo.total }} 道）</el-radio>
          <el-radio value="scan">仅扫描识别入库（{{ clearInfo.scanCount }} 道）</el-radio>
        </el-radio-group>
        <div style="margin:12px 0 6px">请输入 <b>CLEAR</b> 以确认操作</div>
        <el-input v-model="clearWord" placeholder="输入 CLEAR" />
        <el-checkbox v-model="clearAware" style="margin-top:10px">
          我已知悉：题目记录将被永久删除，试卷中对该题的引用会一并移除
        </el-checkbox>
      </div>
      <template #footer>
        <el-button @click="clearDlg = false">取消</el-button>
        <el-button type="danger" :loading="clearLoading" :disabled="!canClear" @click="doClear">
          {{ canClear ? '确认清空' : '请输入 CLEAR 并勾选确认' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" :title="editMode ? (isCreate ? '手动新增题目' : '编辑题目') : '题目详情'" size="520px" :close-on-click-modal="false">
      <template v-if="cur">
        <!-- 编辑模式 -->
        <el-form v-if="editMode" label-position="top" size="small">
          <el-form-item label="题干">
            <el-input v-model="form.stem" type="textarea" :rows="5" />
          </el-form-item>
          <el-form-item label="选项">
            <div v-for="(o, i) in optRows" :key="i" style="display:flex;gap:6px;margin-bottom:6px;width:100%">
              <el-input v-model="o.key" style="width:60px" />
              <el-input v-model="o.content" placeholder="选项内容" />
              <el-button type="danger" plain @click="removeOpt(i)">删除</el-button>
            </div>
            <el-button size="small" @click="addOpt">+ 添加选项</el-button>
          </el-form-item>
          <el-row :gutter="10">
            <el-col :span="12">
              <el-form-item label="题型">
                <el-select v-model="form.qType" style="width:100%">
                  <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="题号">
                <el-input v-model="form.qNo" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="学科">
                <el-select v-model="form.subject" allow-create filterable style="width:100%">
                  <el-option v-for="s in subjectOpts" :key="s" :label="s" :value="s" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="年级">
                <el-input v-model="form.grade" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="课号">
                <el-input v-model="form.courseNo" placeholder="如 K03 / 第3课 / 3" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="难度">
                <el-rate v-model="form.difficulty" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="分值">
                <el-input-number v-model="form.score" :min="0.5" :step="0.5" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item :label="form.qType === 'CHOICE' ? '选择答案' : '答案'">
            <!-- 选择题：按选项点选答案 -->
            <el-radio-group v-if="form.qType === 'CHOICE'" v-model="form.answer" size="small">
              <el-radio-button v-for="k in choiceKeys" :key="k" :value="k">{{ k }}</el-radio-button>
              <el-radio-button value="">空</el-radio-button>
            </el-radio-group>
            <el-radio-group v-else-if="form.qType === 'JUDGE'" v-model="form.answer" size="small">
              <el-radio-button value="√">√ 对</el-radio-button>
              <el-radio-button value="×">× 错</el-radio-button>
              <el-radio-button value="">空</el-radio-button>
            </el-radio-group>
            <el-input v-else v-model="form.answer" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="解析">
            <el-input v-model="form.analysis" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="知识点">
            <el-input v-model="form.knowledge" />
          </el-form-item>
          <el-form-item label="配图">
            <div v-if="images.length" class="img-list">
              <div v-for="(u, i) in images" :key="u + i" class="img-item">
                <el-image :src="u" :preview-src-list="images" :initial-index="i" preview-teleported
                          fit="cover" style="width:88px;height:88px;border-radius:4px;border:1px solid #eee" />
                <el-button type="danger" text size="small" @click="images.splice(i, 1)">移除</el-button>
              </div>
            </div>
            <el-upload :show-file-list="false" :http-request="uploadImage"
                       accept="image/png,image/jpeg,image/gif,image/bmp">
              <el-button size="small" :loading="uploadingImg">+ 上传配图</el-button>
            </el-upload>
            <div class="hint">配图会随题干显示并插入导出的 Word；移除仅取消引用，不删除原文件。</div>
          </el-form-item>
        </el-form>

        <!-- 详情模式 -->
        <template v-else>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="题号">{{ cur.qNo }}</el-descriptions-item>
            <el-descriptions-item label="题型">{{ typeLabel(cur.qType) }}</el-descriptions-item>
            <el-descriptions-item label="题干">{{ cur.stem }}</el-descriptions-item>
            <el-descriptions-item v-if="cur.hasImage === 1 && imgList(cur.imagePath).length" label="配图">
              <div style="display:flex;flex-wrap:wrap;gap:8px">
                <el-image v-for="(u, i) in imgList(cur.imagePath)" :key="i" :src="u"
                          :preview-src-list="imgList(cur.imagePath)" :initial-index="i" preview-teleported
                          fit="contain"
                          style="max-width:180px;max-height:180px;border:1px solid #eee;border-radius:4px" />
              </div>
            </el-descriptions-item>
            <el-descriptions-item v-if="optList.length" label="选项">
              <div v-for="o in optList" :key="o.key">{{ o.key }}. {{ o.content }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="答案">{{ cur.answer || '（暂无）' }}</el-descriptions-item>
            <el-descriptions-item label="解析">{{ cur.analysis || '（暂无）' }}</el-descriptions-item>
            <el-descriptions-item label="知识点">{{ cur.knowledge || '（暂无）' }}</el-descriptions-item>
            <el-descriptions-item label="课号">{{ cur.courseNo || '（未设置）' }}</el-descriptions-item>
            <el-descriptions-item label="入库时间">{{ fmtTime(cur.createTime) }}</el-descriptions-item>
            <el-descriptions-item v-if="cur.updateTime && cur.updateTime !== cur.createTime" label="更新时间">{{ fmtTime(cur.updateTime) }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="simOfCur.length" style="margin-top:16px">
            <h4>相似题推荐</h4>
            <div v-for="s in simOfCur" :key="s.question.id" class="sim-item">
              <el-tag size="small" type="success">{{ s.similarity }}%</el-tag>
              {{ s.question.stem.slice(0, 60) }}
            </div>
          </div>
        </template>
      </template>
      <template #footer>
        <template v-if="editMode">
          <el-button :disabled="isCreate || navIdx < 0" @click="goPrev">上一题</el-button>
          <el-button :disabled="isCreate || navIdx < 0" @click="goNext">下一题</el-button>
          <span v-if="!isCreate && navIdx >= 0" class="nav-pos">
            第 {{ (q.page - 1) * q.size + navIdx + 1 }} / {{ total }} 题
          </span>
          <el-button @click="cancelEdit">{{ isCreate ? '取消' : '取消编辑' }}</el-button>
          <el-button type="primary" :loading="saving" @click="saveEdit">{{ isCreate ? '新增入库' : '保存' }}</el-button>
        </template>
        <template v-else>
          <el-button @click="goPrev">上一题</el-button>
          <el-button @click="goNext">下一题</el-button>
          <span v-if="navIdx >= 0" class="nav-pos">
            第 {{ (q.page - 1) * q.size + navIdx + 1 }} / {{ total }} 题
          </span>
          <el-button type="warning" @click="switchEdit">转为编辑</el-button>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'

const q = reactive({ page: 1, size: 10, keyword: '', qType: '', subject: '', difficulty: null, sortBy: '', sortOrder: '', courseNo: [], courseNoEmpty: false })
const rows = ref([]); const total = ref(0); const loading = ref(false)
const drawer = ref(false); const cur = ref(null); const simOfCur = ref([])
const simText = ref(false); const simInput = ref(''); const simResults = ref([]); const simLoading = ref(false)
const typeLabel = t => Q_TYPE_LABELS[t] || t
const fmtTime = t => (t || '').slice(0, 16)
const imgList = p => (p || '').split(',').map(s => s.trim()).filter(Boolean)
const courseNoOpts = ref([])
async function loadCourseNos() {
  courseNoOpts.value = await http.get('/question/course-nos').catch(() => [])
}
const optList = computed(() => {
  try { return JSON.parse(cur.value?.options || '[]') } catch (e) { return [] }
})

// ---------- 题目编辑 ----------
const subjectOpts = ['数学', '语文', '英语', '物理', '化学', '科学']
const editMode = ref(false); const saving = ref(false)
const form = ref({}); const optRows = ref([])
const images = ref([]); const uploadingImg = ref(false)

function parseOpts(raw) {
  try {
    const arr = JSON.parse(raw || '[]')
    if (Array.isArray(arr)) return arr.map(o => ({ key: o.key || '', content: o.content || '' }))
    return Object.entries(arr || {}).map(([k, v]) => ({ key: k, content: String(v) }))
  } catch (e) {
    return []
  }
}

function buildForm(row) {
  return {
    id: row.id, qNo: row.qNo || '', qType: row.qType || 'OTHER', stem: row.stem || '',
    answer: row.answer || '', analysis: row.analysis || '', knowledge: row.knowledge || '',
    subject: row.subject || '', grade: row.grade || '',
    difficulty: Number(row.difficulty) || 3, score: Number(row.score) || 5,
    courseNo: row.courseNo || '',
    source: row.source || 'SCAN',
  }
}

function startEdit(row) {
  cur.value = row || cur.value
  if (!cur.value) return
  form.value = buildForm(cur.value)
  optRows.value = parseOpts(cur.value.options)
  images.value = imgList(cur.value.imagePath)
  editMode.value = true
  drawer.value = true
  simOfCur.value = []
}
function switchEdit() {
  if (!cur.value) return
  startEdit(cur.value)
}
function cancelEdit() {
  editMode.value = false
  if (isCreate.value) drawer.value = false
}
function addOpt() {
  const next = String.fromCharCode(65 + optRows.value.length)
  optRows.value.push({ key: next, content: '' })
}
function removeOpt(i) {
  optRows.value.splice(i, 1)
}

/* 选择题答案按钮按实际选项生成；没有选项时退回 A-D */
const choiceKeys = computed(() => {
  const ks = optRows.value.map(o => (o.key || '').trim().toUpperCase()).filter(Boolean)
  return ks.length ? ks : ['A', 'B', 'C', 'D']
})

async function uploadImage(opt) {
  uploadingImg.value = true
  try {
    const fd = new FormData()
    fd.append('file', opt.file)
    const r = await http.post('/file/upload', fd)
    images.value.push(r.url)
    ElMessage.success('配图已上传')
  } finally {
    uploadingImg.value = false
  }
}

async function saveEdit() {
  if (!(form.value.stem || '').trim()) {
    ElMessage.warning('题干不能为空')
    return
  }
  saving.value = true
  try {
    const opts = optRows.value.filter(o => (o.content || '').trim())
    const creating = isCreate.value
    const body = {
      ...form.value,
      options: JSON.stringify(opts),
      imagePath: images.value.join(','),
      hasImage: images.value.length ? 1 : 0,
    }
    const saved = await http.post('/question', body)
    ElMessage.success(creating ? '已新增题目' : '已保存')
    editMode.value = false
    if (creating) {
      // 手动题 seq=0，排在列表最前：回到第 1 页就能看到
      q.page = 1
      await load()
      const fresh = rows.value.find(r => r.id === saved.id)
      if (fresh) cur.value = fresh
      else drawer.value = false
    } else {
      const savedId = form.value.id
      await load()
      const fresh = rows.value.find(r => r.id === savedId)
      if (fresh) cur.value = fresh
    }
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

/** 列表内联更正课号：只提交 id + 课号，其余字段不传（不会被 updateById 清空） */
async function saveCourseNo(row) {
  try {
    await http.post('/question', {
      id: row.id,
      courseNo: row.courseNo || '',
    })
    ElMessage.success('课号已保存')
    if (cur.value && cur.value.id === row.id) cur.value = { ...cur.value, courseNo: row.courseNo || '' }
  } catch (e) {
    ElMessage.error('课号保存失败：' + (e?.message || e))
    await load()
  }
}

// ---------- 上一题 / 下一题导航 ----------
// 按当前搜索/排序结果顺序在题目间切换，到页边界时自动翻页；
// 编辑中未保存的修改会先询问「保存并切换」还是「放弃修改」。
const navIdx = computed(() =>
  cur.value?.id == null ? -1 : rows.value.findIndex(r => r.id === cur.value.id))

function eqNum(a, b) {
  return (a == null ? null : Number(a)) === (b == null ? null : Number(b))
}

function isDirty() {
  if (!editMode.value || isCreate.value || !cur.value) return false
  const c = cur.value, f = form.value
  const optsA = optRows.value.filter(o => (o.content || '').trim())
  const optsB = parseOpts(c.options)
  return (f.stem || '') !== (c.stem || '')
    || (f.answer || '') !== (c.answer || '')
    || (f.analysis || '') !== (c.analysis || '')
    || (f.knowledge || '') !== (c.knowledge || '')
    || (f.qNo || '') !== (c.qNo || '')
    || f.qType !== c.qType
    || (f.subject || '') !== (c.subject || '')
    || (f.grade || '') !== (c.grade || '')
    || !eqNum(f.difficulty, c.difficulty)
    || !eqNum(f.score, c.score)
    || (f.courseNo || '') !== (c.courseNo || '')
    || JSON.stringify(optsA) !== JSON.stringify(optsB)
    || images.value.join(',') !== imgList(c.imagePath).join(',')
}

async function gotoQuestion(targetId) {
  let target = rows.value.find(r => r.id === targetId)
  if (!target) return
  if (isDirty()) {
    try {
      await ElMessageBox.confirm('当前题目的修改尚未保存，是否保存后再切换？', '未保存的修改', {
        confirmButtonText: '保存并切换',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning',
      })
      await saveEdit()
    } catch (e) {
      if (e === 'close') return // 直接关闭弹窗：留在当前题
      // 「放弃修改」：继续切换
    }
  }
  target = rows.value.find(r => r.id === targetId) || target
  cur.value = target
  if (editMode.value && !isCreate.value) {
    form.value = buildForm(target)
    optRows.value = parseOpts(target.options)
    images.value = imgList(target.imagePath)
  } else if (!editMode.value) {
    simOfCur.value = await http.get(`/question/${target.id}/similar?topK=5`).catch(() => [])
  }
}

async function goNext() {
  const i = navIdx.value
  if (i < 0) return
  if (i < rows.value.length - 1) return gotoQuestion(rows.value[i + 1].id)
  if (q.page < Math.ceil(total.value / q.size)) {
    q.page += 1
    await load()
    if (rows.value.length) return gotoQuestion(rows.value[0].id)
  }
  ElMessage.info('已经是最后一题')
}

async function goPrev() {
  const i = navIdx.value
  if (i < 0) return
  if (i > 0) return gotoQuestion(rows.value[i - 1].id)
  if (q.page > 1) {
    q.page -= 1
    await load()
    if (rows.value.length) return gotoQuestion(rows.value[rows.value.length - 1].id)
  }
  ElMessage.info('已经是第一题')
}

// ---------- 手动新增题目 ----------
const isCreate = computed(() => editMode.value && !form.value.id)

function startCreate() {
  cur.value = { source: 'MANUAL' }
  form.value = {
    id: null, qNo: '', qType: 'CHOICE', stem: '',
    answer: '', analysis: '', knowledge: '',
    subject: q.subject || '数学', grade: '',
    difficulty: 3, score: 5, courseNo: '', source: 'MANUAL',
  }
  optRows.value = ['A', 'B', 'C', 'D'].map(k => ({ key: k, content: '' }))
  images.value = []
  editMode.value = true
  drawer.value = true
  simOfCur.value = []
}

// 题型切到选择题且还没有选项时，自动预置 A-D 四个空选项
watch(() => form.value.qType, t => {
  if (t === 'CHOICE' && !optRows.value.length) {
    optRows.value = ['A', 'B', 'C', 'D'].map(k => ({ key: k, content: '' }))
  }
})

async function load() {
  loading.value = true
  try {
    const d = await http.post('/question/search', { ...q })
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally { loading.value = false }
}

// 表头点击排序：交给后端全库排序，避免只排当前页
function onSortChange({ prop, order }) {
  q.sortBy = order ? prop : ''
  q.sortOrder = order === 'descending' ? 'desc' : order === 'ascending' ? 'asc' : ''
  q.page = 1
  load()
}

async function openDetail(row) {
  cur.value = row
  drawer.value = true
  simOfCur.value = await http.get(`/question/${row.id}/similar?topK=5`).catch(() => [])
}

async function findSim(row) {
  cur.value = row
  simOfCur.value = await http.get(`/question/${row.id}/similar?topK=5`).catch(() => [])
  drawer.value = true
}

async function doSim() {
  if (!simInput.value.trim()) return
  simLoading.value = true
  try {
    simResults.value = await http.post('/question/similar-by-text', { text: simInput.value })
  } finally { simLoading.value = false }
}

async function del(id) {
  await http.delete(`/question/${id}`)
  ElMessage.success('已删除')
  load()
}

const selection = ref([]); const delLoading = ref(false)
async function delBatch() {
  delLoading.value = true
  try {
    await http.post('/question/batch-delete', selection.value.map(r => r.id))
    ElMessage.success(`已删除 ${selection.value.length} 道题`)
    selection.value = []
    load()
  } finally { delLoading.value = false }
}

// ---------- 导出题库 ----------
const expLoading = ref(false)
async function exportBank(onlySelected) {
  if (onlySelected && !selection.value.length) {
    ElMessage.warning('请先勾选要导出的题目')
    return
  }
  expLoading.value = true
  try {
    const body = onlySelected ? { ids: selection.value.map(r => r.id) } : { ...q }
    const resp = await fetch('/api/question/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!resp.ok) throw new Error('导出失败')
    const blob = await resp.blob()
    const cd = resp.headers.get('Content-Disposition') || ''
    const m = cd.match(/filename\*=UTF-8''(.+)/)
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = m ? decodeURIComponent(m[1]) : '题库导出.xlsx'
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success(onlySelected ? `已导出选中的 ${selection.value.length} 道题` : '已导出当前筛选的题目')
  } catch (e) {
    ElMessage.error('导出失败：' + (e?.message || e))
  } finally {
    expLoading.value = false
  }
}

// ---------- 清空题库 ----------
const clearDlg = ref(false); const clearInfo = ref(null)
const clearScope = ref('all'); const clearWord = ref(''); const clearAware = ref(false); const clearLoading = ref(false)
const canClear = computed(() => clearWord.value.trim().toUpperCase() === 'CLEAR' && clearAware.value)

async function openClear() {
  const d = await http.get('/question/clear-preview').catch(() => null)
  if (!d) { ElMessage.error('获取题库统计失败'); return }
  clearInfo.value = d
  clearScope.value = 'all'
  clearWord.value = ''
  clearAware.value = false
  clearDlg.value = true
}

async function doClear() {
  if (!canClear.value) return
  clearLoading.value = true
  try {
    const r = await http.delete(`/question/clear?scope=${clearScope.value}&confirm=CLEAR`)
    ElMessage.success(`已清空 ${r.deletedQuestions} 道题${r.deletedPaperItems ? `，并移除 ${r.deletedPaperItems} 条试卷引用` : ''}`)
    clearDlg.value = false
    selection.value = []
    await load()
  } catch (e) {
    ElMessage.error('清空失败：' + (e?.message || e))
  } finally {
    clearLoading.value = false
  }
}

onMounted(async () => {
  await load()
  await loadCourseNos()
})
</script>

<style scoped>
.sim-item { padding: 6px 0; border-bottom: 1px dashed #eee; font-size: 13px; }
.img-list { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.img-item { display: flex; flex-direction: column; align-items: center; }
.hint { font-size: 12px; color: #909399; margin-top: 6px; }
.nav-pos { margin: 0 10px; font-size: 12px; color: #909399; }
</style>
