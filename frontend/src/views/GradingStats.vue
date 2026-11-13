<template>
  <div>
    <div class="head-row">
      <div>
        <h2 class="page-title">阅卷统计</h2>
        <div class="sub-title">以「试卷 + 阅卷日期」界定本场考试，数据实时来自阅卷记录</div>
      </div>
      <el-button @click="goGrading">返回扫描阅卷</el-button>
    </div>

    <div class="page-card">
      <el-form inline>
        <el-form-item label="本场考试">
          <el-select v-model="paperId" style="width:280px" placeholder="选择试卷" @change="load">
            <el-option v-for="p in papers" :key="p.id"
                       :label="`${p.title}（${fmt(p.totalScore)} 分）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级">
          <el-select v-model="className" clearable placeholder="全部班级" style="width:170px" @change="load">
            <el-option v-for="c in classNames" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="阅卷日期">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" range-separator="至"
                          style="width:250px" :clearable="true" @change="load" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="load">刷新统计</el-button>
        </el-form-item>
      </el-form>
      <div class="scope-tip">
        当前范围：<b>{{ stats?.paper?.title || '—' }}</b>
        <span v-if="className">· {{ className }}</span>
        <span v-if="dateRange?.length === 2">· {{ dateRange[0] }} 至 {{ dateRange[1] }}</span>
        <span v-if="stats">· 共 {{ stats.count }} 份有效成绩</span>
        <span class="tip-note">（及格线 {{ fmt(stats?.passLine) }} 分，优秀线 {{ fmt(stats?.excellentLine) }} 分）</span>
      </div>
    </div>

    <template v-if="stats && stats.count > 0">
      <!-- 四项核心指标 -->
      <div class="kpi-grid">
        <div v-for="k in kpis" :key="k.label" class="kpi-card" :style="{ '--tone': k.color }">
          <div class="kpi-top">
            <span class="kpi-badge">{{ k.badge }}</span>
            <span class="kpi-label">{{ k.label }}</span>
          </div>
          <div class="kpi-value">{{ k.value }}<span class="kpi-unit">分</span></div>
          <div class="kpi-foot">{{ k.foot }}</div>
        </div>
      </div>

      <!-- 辅助指标 -->
      <div class="page-card metric-strip">
        <div v-for="m in metrics" :key="m.label" class="metric">
          <div class="metric-value">{{ m.value }}</div>
          <div class="metric-label">{{ m.label }}</div>
        </div>
      </div>

      <!-- 图表 -->
      <div class="chart-grid">
        <div class="page-card chart-card">
          <div class="card-head">
            <h3>分数段人数分布</h3>
            <span>每 {{ fmt(stats.segmentWidth) }} 分一档，末档含满分</span>
          </div>
          <BarChart :labels="distLabels" :values="distValues" color="#409EFF" unit="人" />
        </div>

        <div class="page-card chart-card">
          <div class="card-head">
            <h3>各班平均分对比</h3>
            <span>按平均分降序</span>
          </div>
          <div v-if="classBars.length" class="hbar-list">
            <div v-for="c in classBars" :key="c.name" class="hbar-row">
              <div class="hbar-name" :title="c.name">{{ c.name }}</div>
              <div class="hbar-track">
                <div class="hbar-fill" :style="{ width: c.pct + '%' }"></div>
              </div>
              <div class="hbar-num">{{ fmt(c.avg) }} <small>/ {{ c.count }}人</small></div>
            </div>
            <div class="hbar-legend">横轴满格 = 卷面总分 {{ fmt(stats.fullScore) }} 分</div>
          </div>
          <el-empty v-else description="暂无班级数据（可在阅卷记录中补录班级）" :image-size="70" />
        </div>
      </div>

      <div class="page-card">
        <div class="card-head">
          <h3>成绩走势</h3>
          <span>按阅卷先后顺序排列，橙色虚线为平均分</span>
        </div>
        <LineChart :values="lineValues" :labels="lineLabels"
                   :avg="Number(stats.avgScore)" :max="Number(stats.fullScore)" />
      </div>

      <!-- 班级汇总 -->
      <div v-if="stats.byClass.length" class="page-card">
        <div class="card-head"><h3>班级成绩汇总</h3></div>
        <el-table :data="stats.byClass" size="small" border>
          <el-table-column prop="className" label="班级" min-width="140" />
          <el-table-column prop="count" label="参考人数" width="100" align="center" />
          <el-table-column label="平均分" width="110" align="center">
            <template #default="{ row }"><b class="score-blue">{{ fmt(row.avgScore) }}</b></template>
          </el-table-column>
          <el-table-column label="最高分" width="110" align="center">
            <template #default="{ row }">{{ fmt(row.maxScore) }}</template>
          </el-table-column>
          <el-table-column label="最低分" width="110" align="center">
            <template #default="{ row }">{{ fmt(row.minScore) }}</template>
          </el-table-column>
          <el-table-column label="及格率" width="120" align="center">
            <template #default="{ row }">{{ fmt(row.passRate) }}%</template>
          </el-table-column>
          <el-table-column label="平均分得分率" min-width="140" align="center">
            <template #default="{ row }">{{ rate(row.avgScore, stats.fullScore) }}%</template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 考生明细 -->
      <div class="page-card">
        <div class="card-head">
          <h3>成绩明细</h3>
          <span>同一考生多份记录已汇总求和；按总分降序，同分并列名次</span>
        </div>
        <el-table :data="stats.ranking" size="small" border max-height="420">
          <el-table-column prop="rank" label="名次" width="76" align="center">
            <template #default="{ row }">
              <span class="rank-badge" :class="{ top: row.rank <= 3 }">{{ row.rank }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="className" label="班级" min-width="120" />
          <el-table-column prop="studentName" label="姓名" min-width="100" />
          <el-table-column prop="studentNo" label="考号" min-width="120">
            <template #default="{ row }">{{ row.studentNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="份数" width="70" align="center">
            <template #default="{ row }">{{ row.recordCount || 1 }}</template>
          </el-table-column>
          <el-table-column label="客观题" width="90" align="center">
            <template #default="{ row }">{{ fmt(row.objectiveScore) }}</template>
          </el-table-column>
          <el-table-column label="主观题" width="90" align="center">
            <template #default="{ row }">{{ fmt(row.subjectiveScore) }}</template>
          </el-table-column>
          <el-table-column label="总分" width="100" align="center">
            <template #default="{ row }"><b class="score-blue">{{ fmt(row.totalScore) }}</b></template>
          </el-table-column>
          <el-table-column label="得分率" min-width="160">
            <template #default="{ row }">
              <div class="rate-cell">
                <div class="rate-track"><div class="rate-fill" :style="{ width: Math.min(100, row.scoreRate) + '%' }"></div></div>
                <span>{{ fmt(row.scoreRate) }}%</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="阅卷时间" width="160" class-name="col-optional">
            <template #default="{ row }">{{ String(row.createTime || '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openGradeDetail(row)">答卷详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 按题查错 -->
      <div class="page-card">
        <div class="card-head">
          <h3>按题查错</h3>
          <span>选择题目，查看该题失分的考生名单</span>
        </div>
        <el-form inline>
          <el-form-item label="题目">
            <el-select v-model="wrongItemId" filterable style="width:400px" placeholder="选择题目">
              <el-option v-for="q in paperQuestions" :key="q.item.id"
                         :label="`第${q.no}题（${q.item.sectionName}）${q.brief}`" :value="q.item.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadWrong" :loading="wrongLoading"
                       :disabled="!wrongItemId">查询失分考生</el-button>
          </el-form-item>
        </el-form>
        <el-table v-if="wrongList.length" :data="wrongList" size="small" border max-height="320">
          <el-table-column prop="className" label="班级" min-width="110" />
          <el-table-column prop="studentName" label="姓名" min-width="100" />
          <el-table-column prop="studentNo" label="考号" min-width="120">
            <template #default="{ row }">{{ row.studentNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="该题得分" width="130" align="center">
            <template #default="{ row }">
              <span :class="{ 'score-blue': row.gotScore > 0 }">{{ fmt(row.gotScore) }} / {{ fmt(row.fullScore) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openGradeDetail({ id: row.gradeId })">看答卷</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="wrongSearched" description="该题没有失分考生（或该批记录无逐题明细）" :image-size="60" />
      </div>
    </template>

    <div v-else-if="!loading" class="page-card">
      <el-empty description="该条件下暂无阅卷成绩，请先在「扫描阅卷」完成阅卷">
        <el-button type="primary" @click="goGrading">去阅卷</el-button>
      </el-empty>
    </div>

    <!-- 考生答卷详情：逐题明细，错题高亮，点题号看该题作答截图 -->
    <el-dialog v-model="gdVisible" width="900px" top="4vh"
               :title="gdGrade ? `答卷详情：${gdGrade.studentName || '待确认'}（${gdGrade.studentNo || '考号未识别'}）` : '答卷详情'">
      <div v-loading="gdLoading">
        <div v-if="gdGrade" class="gd-head">
          <span>客观题 <b>{{ fmt(gdGrade.objectiveScore) }}</b></span>
          <span>主观题 <b>{{ fmt(gdGrade.subjectiveScore) }}</b></span>
          <span>总分 <b class="score-blue">{{ fmt(gdGrade.totalScore) }}</b> / {{ fmt(gdGrade.fullScore) }}</span>
          <el-switch v-model="gdShowImg" active-text="显示作答截图" style="margin-left:auto" />
        </div>
        <el-table v-if="gdDetails.length" :data="gdDetails" size="small" border max-height="360"
                  highlight-current-row :row-class-name="gdRowClass">
          <el-table-column label="题号" width="80" align="center">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="gdPick(row)">{{ row.qNo }}</el-link>
            </template>
          </el-table-column>
          <el-table-column label="大题" min-width="110" class-name="col-optional">
            <template #default="{ row }">{{ row.sectionName }}</template>
          </el-table-column>
          <el-table-column label="题型" width="90" align="center">
            <template #default="{ row }">{{ Q_TYPE_LABELS[row.qType] || row.qType }}</template>
          </el-table-column>
          <el-table-column label="得分" width="100" align="center">
            <template #default="{ row }"><b>{{ fmt(row.gotScore) }}</b> / {{ fmt(row.score) }}</template>
          </el-table-column>
          <el-table-column label="判定" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="verdictTag(row.verdict)">{{ verdictLabel(row.verdict) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="学生作答" min-width="100" align="center">
            <template #default="{ row }">{{ row.studentAnswer || '—' }}</template>
          </el-table-column>
          <el-table-column label="标准答案" min-width="100" align="center">
            <template #default="{ row }">{{ row.standardAnswer || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!gdLoading" description="无逐题明细（旧数据或快速截图模式）" :image-size="60" />

        <div v-if="gdPicked && gdShowImg" class="gd-cell">
          <div class="gd-cell-head">
            <span>第 {{ gdPicked.qNo }} 题作答截图</span>
            <el-button v-if="!isObjective(gdPicked.qType)" size="small" text type="primary"
                       @click="gdOcr" :loading="gdOcrLoading">OCR 识别文字</el-button>
          </div>
          <img class="gd-cell-img" :src="`/api/grading/${gdGradeId}/cell/${gdPicked.paperItemId}`" alt="作答截图">
          <div v-if="gdOcrText !== null && gdOcrFor === gdPicked.paperItemId" class="gd-ocr-text">
            识别文本：{{ gdOcrText || '（未识别出文字，请看截图人工判分）' }}
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { Q_TYPE_LABELS } from '../api'
import BarChart from '../components/BarChart.vue'
import LineChart from '../components/LineChart.vue'

const route = useRoute()
const router = useRouter()

const papers = ref([])
const paperId = ref(route.query.paperId ? Number(route.query.paperId) : null)
const className = ref('')
const dateRange = ref(null)
const stats = ref(null)
const loading = ref(false)

const fmt = v => Number(Number(v || 0).toFixed(1))
const rate = (v, full) => (Number(full) > 0 ? Number((Number(v) / Number(full) * 100).toFixed(1)) : 0)

const classNames = computed(() => stats.value?.classNames || [])
const distLabels = computed(() => (stats.value?.distribution || []).map(d => d.label))
const distValues = computed(() => (stats.value?.distribution || []).map(d => d.count))
const lineValues = computed(() => (stats.value?.scoreLine || []).map(p => Number(p.score)))
const lineLabels = computed(() => (stats.value?.scoreLine || []).map(p => p.label))

const classBars = computed(() => {
  const full = Number(stats.value?.fullScore) || 100
  return (stats.value?.byClass || []).map(c => ({
    name: c.className,
    count: c.count,
    avg: Number(c.avgScore),
    pct: Math.max(2, Math.min(100, Number(c.avgScore) / full * 100)),
  }))
})

const kpis = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    {
      label: '本场考试总分', badge: '卷', color: '#409EFF',
      value: fmt(s.fullScore), foot: `试卷卷面满分 · ${s.paper?.subject || '—'} ${s.paper?.grade || ''}`,
    },
    {
      label: '平均分', badge: '平', color: '#67C23A',
      value: fmt(s.avgScore), foot: `整体得分率 ${rate(s.avgScore, s.fullScore)}%`,
    },
    {
      label: '最高分', badge: '高', color: '#E6A23C',
      value: fmt(s.maxScore), foot: `得分率 ${rate(s.maxScore, s.fullScore)}%`,
    },
    {
      label: '最低分', badge: '低', color: '#9096F0',
      value: fmt(s.minScore), foot: `与最高分相差 ${fmt(Number(s.maxScore) - Number(s.minScore))} 分`,
    },
  ]
})

const metrics = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { label: '参考人数', value: `${s.count} 人` },
    { label: '成绩合计', value: `${fmt(s.sumScore)} 分` },
    { label: '中位数', value: `${fmt(s.medianScore)} 分` },
    { label: '及格率', value: `${fmt(s.passRate)}%` },
    { label: '优秀率', value: `${fmt(s.excellentRate)}%` },
    { label: '平均客观 / 主观', value: `${fmt(s.avgObjective)} / ${fmt(s.avgSubjective)}` },
  ]
})

async function load() {
  if (!paperId.value) {
    stats.value = null
    return
  }
  loading.value = true
  try {
    const params = { paperId: paperId.value }
    if (className.value) params.className = className.value
    if (dateRange.value?.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    stats.value = await http.get('/grading/stats', { params })
  } catch {
    stats.value = null
  } finally {
    loading.value = false
  }
  loadQuestions()
}

// ---------------- 按题查错 ----------------

const paperQuestions = ref([])
const wrongItemId = ref(null)
const wrongList = ref([])
const wrongLoading = ref(false)
const wrongSearched = ref(false)

/** 拉取试卷题目列表，供按题查错下拉选择（卷内题号按大题内顺序重编） */
async function loadQuestions() {
  wrongItemId.value = null
  wrongList.value = []
  wrongSearched.value = false
  if (!paperId.value) {
    paperQuestions.value = []
    return
  }
  const d = await http.get(`/paper/${paperId.value}`).catch(() => null)
  let no = 0
  let prev = null
  paperQuestions.value = (d?.items || []).map(r => {
    if (r.item.sectionNo !== prev) {
      prev = r.item.sectionNo
      no = 1
    } else {
      no++
    }
    const stem = String(r.question?.stem || '').replace(/\s+/g, ' ')
    return { item: r.item, no, brief: stem.length > 26 ? stem.slice(0, 26) + '…' : stem }
  })
}

async function loadWrong() {
  if (!paperId.value || !wrongItemId.value) return
  wrongLoading.value = true
  try {
    wrongList.value = await http.get('/grading/wrong-students', {
      params: { paperId: paperId.value, paperItemId: wrongItemId.value },
    })
  } catch {
    wrongList.value = []
  } finally {
    wrongLoading.value = false
    wrongSearched.value = true
  }
}

// ---------------- 答卷详情弹窗 ----------------

const gdVisible = ref(false)
const gdLoading = ref(false)
const gdGrade = ref(null)
const gdGradeId = ref(null)
const gdDetails = ref([])
const gdPicked = ref(null)
const gdShowImg = ref(true)
const gdOcrText = ref(null)
const gdOcrFor = ref(null)
const gdOcrLoading = ref(false)

async function openGradeDetail(row) {
  gdVisible.value = true
  gdLoading.value = true
  gdGrade.value = null
  gdDetails.value = []
  gdPicked.value = null
  gdOcrText.value = null
  gdGradeId.value = row.id
  try {
    const d = await http.get(`/grading/${row.id}`)
    gdGrade.value = d.grade
    gdDetails.value = d.details || []
    // 默认展开第一道错题的截图，方便直接看错因
    const wrong = gdDetails.value.find(x => Number(x.gotScore) < Number(x.score))
    if (wrong) gdPicked.value = wrong
  } catch {
    /* 拦截器提示 */
  } finally {
    gdLoading.value = false
  }
}

function gdPick(row) {
  gdPicked.value = row
  gdOcrText.value = null
}

function gdRowClass({ row }) {
  return Number(row.gotScore) < Number(row.score) ? 'gd-wrong-row' : ''
}

const verdictLabel = v => ({ CORRECT: '正确', WRONG: '错误', BLANK: '未作答', MANUAL: '人工判分' }[v] || v)
const verdictTag = v => ({ CORRECT: 'success', WRONG: 'danger', BLANK: 'warning', MANUAL: 'info' }[v] || 'info')
const isObjective = t => t === 'CHOICE' || t === 'JUDGE'

async function gdOcr() {
  if (!gdPicked.value || !gdGradeId.value) return
  gdOcrLoading.value = true
  try {
    const r = await http.get(`/grading/${gdGradeId.value}/cell/${gdPicked.value.paperItemId}/ocr`)
    gdOcrText.value = r?.text || ''
    gdOcrFor.value = gdPicked.value.paperItemId
  } catch {
    gdOcrText.value = ''
    gdOcrFor.value = gdPicked.value.paperItemId
  } finally {
    gdOcrLoading.value = false
  }
}

function goGrading() {
  router.push({ path: '/grading', query: paperId.value ? { paperId: paperId.value } : {} })
}

onMounted(async () => {
  papers.value = await http.get('/paper/list').catch(() => [])
  if (!paperId.value) {
    // 默认定位到最近有阅卷记录的试卷；没有成绩时退回到第一份试卷
    const recent = await http.get('/grading/list').catch(() => [])
    paperId.value = recent.find(r => r.paperId)?.paperId || papers.value[0]?.id || null
  }
  // 从考生管理「错题号」链接跳转过来：带 gradeId 时直接打开该生答卷详情
  if (route.query.gradeId) {
    openGradeDetail({ id: route.query.gradeId })
    router.replace({ query: { ...route.query, gradeId: undefined } })
  }
  load()
})
</script>

<style scoped>
.head-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.sub-title { color: #909399; font-size: 12px; margin: -10px 0 14px; }
.scope-tip { font-size: 12px; color: #606266; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.scope-tip b { color: #409EFF; }
.tip-note { color: #a8abb2; margin-left: 8px; }

.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 16px; margin-bottom: 16px; }
.kpi-card {
  position: relative; background: #fff; border-radius: 10px; padding: 18px 20px;
  box-shadow: 0 2px 12px rgba(31, 45, 61, .06); overflow: hidden;
}
.kpi-card::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; background: var(--tone); }
.kpi-top { display: flex; align-items: center; gap: 8px; }
.kpi-badge {
  width: 24px; height: 24px; border-radius: 6px; display: inline-flex; align-items: center;
  justify-content: center; color: #fff; background: var(--tone); font-size: 12px; font-weight: 700;
}
.kpi-label { color: #606266; font-size: 13px; }
.kpi-value { font-size: 32px; font-weight: 700; color: var(--tone); line-height: 1.35; margin-top: 6px; }
.kpi-unit { font-size: 13px; font-weight: 400; margin-left: 4px; color: #909399; }
.kpi-foot { color: #a8abb2; font-size: 12px; margin-top: 2px; }

.metric-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 8px; padding: 14px 20px; }
.metric { text-align: center; border-right: 1px solid #f0f2f5; }
.metric:last-child { border-right: none; }
.metric-value { font-size: 17px; font-weight: 600; color: #303133; }
.metric-label { color: #909399; font-size: 12px; margin-top: 4px; }

.chart-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; }
.chart-card { margin-bottom: 0; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.card-head h3 { margin: 0; font-size: 15px; color: #303133; }
.card-head span { color: #a8abb2; font-size: 12px; }

.hbar-list { padding: 8px 4px 0; }
.hbar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.hbar-name { width: 96px; flex-shrink: 0; font-size: 12px; color: #606266; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hbar-track { flex: 1; height: 16px; background: #f0f2f5; border-radius: 8px; overflow: hidden; }
.hbar-fill {
  height: 100%; border-radius: 8px;
  background: linear-gradient(90deg, #79bbff, #409EFF);
  transition: width .4s ease;
}
.hbar-num { width: 92px; text-align: right; font-size: 13px; font-weight: 600; color: #409EFF; }
.hbar-num small { font-weight: 400; color: #a8abb2; }
.hbar-legend { color: #c0c4cc; font-size: 11px; text-align: right; }

.score-blue { color: #409EFF; }
.rank-badge {
  display: inline-block; min-width: 22px; padding: 1px 6px; border-radius: 10px;
  background: #f4f4f5; color: #909399; font-size: 12px;
}
.rank-badge.top { background: #fdf0d5; color: #e6a23c; font-weight: 700; }
.rate-cell { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #606266; }
.rate-track { flex: 1; min-width: 60px; height: 7px; background: #f0f2f5; border-radius: 4px; overflow: hidden; }
.rate-fill { height: 100%; background: linear-gradient(90deg, #67C23A, #409EFF); border-radius: 4px; }

/* ---------- 答卷详情弹窗 ---------- */
.gd-head { display: flex; gap: 24px; align-items: center; margin-bottom: 10px; font-size: 13px; color: #606266; }
.gd-head b { color: #303133; }
:deep(.gd-wrong-row) { --el-table-tr-bg-color: #fef0f0; }
.gd-cell { margin-top: 14px; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.gd-cell-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.gd-cell-img { max-width: 100%; max-height: 320px; border: 1px solid #eee; border-radius: 4px; display: block; margin: 0 auto; }
.gd-ocr-text { margin-top: 8px; font-size: 13px; color: #67C23A; background: #f6ffed; padding: 6px 10px; border-radius: 4px; }

@media (max-width: 768px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .kpi-card { padding: 12px; }
  .kpi-value { font-size: 24px; }
  .metric-strip { grid-template-columns: repeat(2, 1fr); padding: 10px; }
  .chart-grid { grid-template-columns: 1fr; }
  .hbar-name { width: 70px; }
}
</style>
