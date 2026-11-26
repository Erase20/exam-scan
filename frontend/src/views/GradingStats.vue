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

      <!-- 错题统计：按出错率降序，可勾选错题重新组卷 -->
      <div class="page-card">
        <div class="card-head">
          <h3>错题统计</h3>
          <span>按出错率由高到低；点行首展开失分考生名单</span>
          <el-button type="primary" size="small" style="margin-left:auto"
                     :disabled="!pickedWrong.length" @click="openRebuild">
            错题重新组卷（{{ pickedWrong.length }}）
          </el-button>
        </div>
        <div v-if="!paperId" class="muted" style="padding:8px 0">请先在上方选择试卷，再查看错题统计</div>
        <el-table v-else :data="wrongQs" v-loading="wrongQLoading" size="small" border max-height="420"
                  row-key="paperItemId" @selection-change="s => pickedWrong = s">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding:4px 12px 8px">
                <el-table v-if="row.wrongStudents.length" :data="row.wrongStudents" size="small" border>
                  <el-table-column prop="className" label="班级" min-width="100" />
                  <el-table-column prop="studentName" label="姓名" min-width="90" />
                  <el-table-column prop="studentNo" label="考号" min-width="110">
                    <template #default="{ row: r }">{{ r.studentNo || '—' }}</template>
                  </el-table-column>
                  <el-table-column label="判定" width="80" align="center">
                    <template #default="{ row: r }">
                      <el-tag size="small" :type="verdictTag(r.verdict)">{{ verdictLabel(r.verdict) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="作答" min-width="120" show-overflow-tooltip>
                    <template #default="{ row: r }">{{ r.studentAnswer || '—' }}</template>
                  </el-table-column>
                  <el-table-column label="该题得分" width="100" align="center">
                    <template #default="{ row: r }">{{ fmt(r.gotScore) }} / {{ fmt(r.fullScore) }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="90" align="center">
                    <template #default="{ row: r }">
                      <el-button link type="primary" size="small"
                                 @click="openGradeDetail({ id: r.gradeId }, r.qNo)">看答卷</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="该题没有失分考生" :image-size="50" />
              </div>
            </template>
          </el-table-column>
          <el-table-column type="selection" width="42" :selectable="row => !!row.questionId" />
          <el-table-column label="题号" width="70" align="center">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="openWrongQ(row)">{{ row.qNo }}</el-link>
            </template>
          </el-table-column>
          <el-table-column label="大题" min-width="110">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="openWrongQ(row)">{{ row.sectionName }}</el-link>
            </template>
          </el-table-column>
          <el-table-column label="题型" width="90" align="center">
            <template #default="{ row }"><el-tag size="small">{{ typeLabel(row.qType) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="分值" width="70" align="center">
            <template #default="{ row }">{{ fmt(row.score) }}</template>
          </el-table-column>
          <el-table-column label="出错率" width="180" align="center">
            <template #default="{ row }">
              <el-progress :percentage="row.wrongRate" :stroke-width="12"
                           :color="row.wrongRate >= 60 ? '#F56C6C' : row.wrongRate >= 30 ? '#E6A23C' : '#67C23A'" />
            </template>
          </el-table-column>
          <el-table-column label="失分人数" width="100" align="center">
            <template #default="{ row }">{{ row.wrongCount }} / {{ row.answered }}</template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <div v-else-if="!loading" class="page-card">
      <el-empty description="该条件下暂无阅卷成绩，请先在「扫描阅卷」完成阅卷">
        <el-button type="primary" @click="goGrading">去阅卷</el-button>
      </el-empty>
    </div>

    <!-- 错题详情：点击错题统计的题号/大题打开，显示题目内容与失分考生 -->
    <el-dialog v-model="wqVisible" :width="wqFull ? '96%' : '820px'" :top="wqFull ? '1vh' : '5vh'">
      <template #header>
        <div class="gd-dialog-head">
          <span>{{ wqQ ? `第 ${wqQ.qNo} 题（${wqQ.sectionName}）` : '错题详情' }}</span>
          <el-button link type="primary" size="small" style="margin-left:12px" @click="wqFull = !wqFull">
            {{ wqFull ? '还原' : '⤢ 最大化' }}
          </el-button>
        </div>
      </template>
      <div v-if="wqQ">
        <div class="wq-meta">
          <el-button-group style="margin-right:8px">
            <el-button size="small" :disabled="wqIdx <= 0" @click="wqStep(-1)">← 上一题</el-button>
            <el-button size="small" :disabled="wqIdx < 0 || wqIdx >= wrongQs.length - 1" @click="wqStep(1)">
              下一题 →
            </el-button>
          </el-button-group>
          <span style="color:#909399;font-size:12px">{{ wqIdx + 1 }} / {{ wrongQs.length }}（按出错率排序）</span>
          <el-tag size="small" style="margin-left:12px">{{ typeLabel(wqQ.qType) }}</el-tag>
          <span>分值 <b>{{ fmt(wqQ.score) }}</b> 分</span>
          <span v-if="wqInfo.difficulty">难度 {{ wqInfo.difficulty }}★</span>
          <span>出错率 <b class="wq-rate">{{ wqQ.wrongRate }}%</b>（失分 {{ wqQ.wrongCount }} / {{ wqQ.answered }} 人）</span>
          <el-switch v-model="wqHideAnswer" active-text="隐藏答案" style="margin-left:auto" />
        </div>
        <div class="wq-stem" :class="{ big: wqFull }"><b>题干：</b>{{ wqInfo.stem || '（无题干）' }}</div>
        <div v-if="wqInfo.options && wqInfo.options.length" class="wq-opts" :class="{ big: wqFull }">
          <span v-for="o in wqInfo.options" :key="o.key" class="wq-opt">{{ o.key }}．{{ o.content }}</span>
        </div>
        <template v-if="!wqHideAnswer">
          <div class="wq-block ans" :class="{ big: wqFull }"><b>标准答案：</b>{{ wqInfo.answer || '—' }}</div>
          <div v-if="wqInfo.analysis" class="wq-block" :class="{ big: wqFull }"><b>解析：</b>{{ wqInfo.analysis }}</div>
        </template>
        <div v-else class="wq-answer-mask" @click="wqHideAnswer = false">答案已隐藏，点击开关或此处显示标准答案</div>
        <div style="font-weight:600;margin:14px 0 6px">失分考生（{{ wqQ.wrongStudents.length }} 人）</div>
        <el-table v-if="wqQ.wrongStudents.length" :data="wqQ.wrongStudents" size="small" border max-height="240">
          <el-table-column prop="className" label="班级" min-width="100" />
          <el-table-column prop="studentName" label="姓名" min-width="90" />
          <el-table-column prop="studentNo" label="考号" min-width="110">
            <template #default="{ row: r }">{{ r.studentNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="判定" width="80" align="center">
            <template #default="{ row: r }">
              <el-tag size="small" :type="verdictTag(r.verdict)">{{ verdictLabel(r.verdict) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="作答" min-width="120" show-overflow-tooltip>
            <template #default="{ row: r }">{{ r.studentAnswer || '—' }}</template>
          </el-table-column>
          <el-table-column label="该题得分" width="100" align="center">
            <template #default="{ row: r }">{{ fmt(r.gotScore) }} / {{ fmt(r.fullScore) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row: r }">
              <el-button link type="primary" size="small"
                         @click="openGradeDetail({ id: r.gradeId }, r.qNo)">看答卷</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无失分考生" :image-size="50" />
      </div>
    </el-dialog>

    <!-- 考生答卷详情：逐题明细，错题高亮，点行切换查看该题作答截图 -->
    <el-dialog v-model="gdVisible" :width="gdFull ? '98%' : '900px'" :top="gdFull ? '1vh' : '4vh'">
      <template #header>
        <div class="gd-dialog-head">
          <span>{{ gdGrade ? `答卷详情：${gdGrade.studentName || '待确认'}（${gdGrade.studentNo || '考号未识别'}）` : '答卷详情' }}</span>
          <el-button link type="primary" size="small" style="margin-left:12px" @click="gdFull = !gdFull">
            {{ gdFull ? '还原' : '⤢ 最大化' }}
          </el-button>
        </div>
      </template>
      <div v-loading="gdLoading">
        <el-alert v-if="gdDetail && gdDetail.inLibrary === false" type="warning" :closable="false"
                  style="margin-bottom:10px"
                  title="⚠️ 该考生不在考生库中（考生须先在「考生管理」导入/录入），请核对答题卡后到阅卷记录中修正考生信息" />
        <div v-if="gdGrade" class="gd-head">
          <span>客观题 <b>{{ fmt(gdGrade.objectiveScore) }}</b></span>
          <span>主观题 <b>{{ fmt(gdGrade.subjectiveScore) }}</b></span>
          <span>总分 <b class="score-blue">{{ fmt(gdGrade.totalScore) }}</b> / {{ fmt(gdGrade.fullScore) }}</span>
          <el-switch v-model="gdShowImg" active-text="显示作答截图" style="margin-left:auto" />
        </div>
        <el-table v-if="gdDetails.length" :data="gdDetails" size="small" border max-height="360"
                  highlight-current-row :row-class-name="gdRowClass"
                  @row-click="r => gdPick(r)" style="cursor:pointer">
          <el-table-column label="题号" width="80" align="center">
            <template #default="{ row }">{{ row.qNo }}</template>
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
            <span>第 {{ gdPicked.qNo }} 题（{{ gdPicked.sectionName }}）</span>
            <el-button v-if="!isObjective(gdPicked.qType)" size="small" text type="primary"
                       @click="gdOcr" :loading="gdOcrLoading">OCR 识别文字</el-button>
          </div>
          <div v-if="gdStem" class="gd-stem">
            <b>题干：</b>{{ gdStem }}
          </div>
          <div v-if="gdOpts.length" class="gd-opts" :class="{ big: gdFull }">
            <span v-for="o in gdOpts" :key="o.key">{{ o.key }}．{{ o.content }}</span>
          </div>
          <div v-if="gdPicked.standardAnswer" class="gd-ans" :class="{ big: gdFull }">
            <b>标准答案：</b>{{ gdPicked.standardAnswer }}
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
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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
  loadWrongQuestions()
}

// ---------------- 错题统计（按出错率降序）+ 错题重新组卷 ----------------

const typeLabel = t => Q_TYPE_LABELS[t] || t
const wrongQs = ref([])
const wrongQLoading = ref(false)
const pickedWrong = ref([])
const stemMap = ref({})   // paperItemId -> 题干，答卷详情弹窗显示题干用
const wqInfoMap = ref({}) // paperItemId -> 题目信息（题干/答案/解析/难度），错题详情弹窗用

/** 聚合本场考试每题的失分人数与出错率（后端按出错率降序返回），同时加载题目信息映射 */
async function loadWrongQuestions() {
  pickedWrong.value = []
  stemMap.value = {}
  wqInfoMap.value = {}
  if (!paperId.value) {
    wrongQs.value = []
    return
  }
  wrongQLoading.value = true
  try {
    wrongQs.value = await http.get('/grading/wrong-questions', { params: { paperId: paperId.value } })
  } catch {
    wrongQs.value = []
  } finally {
    wrongQLoading.value = false
  }
  const d = await http.get(`/paper/${paperId.value}`).catch(() => null)
  const stems = {}
  const infos = {}
  for (const r of d?.items || []) {
    stems[r.item.id] = String(r.question?.stem || '').replace(/\s+/g, ' ').slice(0, 220)
    let opts = []
    try { opts = JSON.parse(r.question?.options || '[]') || [] } catch (e) { opts = [] }
    infos[r.item.id] = {
      stem: String(r.question?.stem || '').trim(),
      answer: r.question?.answer || '',
      analysis: r.question?.analysis || '',
      difficulty: r.question?.difficulty || null,
      options: opts.filter(o => o && o.content),
    }
  }
  stemMap.value = stems
  wqInfoMap.value = infos
}

// ---------------- 错题详情（点大题/题号打开） ----------------
const wqVisible = ref(false)
const wqFull = ref(false)          // 最大化
const wqHideAnswer = ref(false)    // 隐藏标准答案（讲评时先让学生思考）
const wqQ = ref(null)
const wqInfo = computed(() => (wqQ.value && wqInfoMap.value[wqQ.value.paperItemId]) || {})
/** 当前题在错题列表中的位置，供上一题/下一题切换 */
const wqIdx = computed(() => wrongQs.value.findIndex(x => x.paperItemId === wqQ.value?.paperItemId))
function openWrongQ(row) {
  wqQ.value = row
  wqVisible.value = true
}
function wqStep(delta) {
  const i = wqIdx.value + delta
  if (i >= 0 && i < wrongQs.value.length) {
    wqQ.value = wrongQs.value[i]
  }
}

/** 把勾选的错题重新组卷（新建「错题重练」试卷，题目按题型自动归入大题） */
async function openRebuild() {
  const qids = [...new Set(pickedWrong.value.map(w => w.questionId).filter(Boolean))]
  if (!qids.length) return
  try {
    const { value } = await ElMessageBox.prompt(
      `已选 ${qids.length} 道错题，将新建试卷并按题型自动归入大题（重复题自动跳过）`,
      '错题重新组卷',
      {
        inputValue: `${stats.value?.paper?.title || '考试'}错题重练`,
        inputValidator: v => (v && v.trim() ? true : '请输入试卷标题'),
        confirmButtonText: '创建试卷',
      })
    const r = await http.post('/paper/add-questions', {
      questionIds: qids,
      newTitle: value.trim(),
      newSubject: stats.value?.paper?.subject || null,
      newGrade: stats.value?.paper?.grade || null,
      newDuration: 45,
    })
    ElMessage.success(`已创建试卷「${r.paperTitle}」：收入 ${r.added} 道错题${r.skipped ? `，跳过重复 ${r.skipped} 道` : ''}`)
  } catch (e) {
    if (e !== 'cancel') { /* 非 ESC/取消的错误由拦截器提示 */ }
  }
}

// ---------------- 答卷详情弹窗 ----------------

const gdVisible = ref(false)
const gdFull = ref(false)
const gdLoading = ref(false)
const gdGrade = ref(null)
const gdDetail = ref(null)
const gdGradeId = ref(null)
const gdDetails = ref([])
const gdPicked = ref(null)
const gdShowImg = ref(true)
const gdOcrText = ref(null)
const gdOcrFor = ref(null)
const gdOcrLoading = ref(false)

/** 当前选中题的题干（从试卷题目映射取，无则不显示） */
const gdStem = computed(() => (gdPicked.value && stemMap.value[gdPicked.value.paperItemId]) || '')
/** 当前选中题的选项（选择/判断题显示，便于对照作答） */
const gdOpts = computed(() => ((gdPicked.value && wqInfoMap.value[gdPicked.value.paperItemId]) || {}).options || [])

async function openGradeDetail(row, targetQNo) {
  if (!row || !row.id) {
    ElMessage.error('该记录缺少编号，无法打开答卷详情')
    return
  }
  gdVisible.value = true
  gdLoading.value = true
  gdGrade.value = null
  gdDetail.value = null
  gdDetails.value = []
  gdPicked.value = null
  gdOcrText.value = null
  gdGradeId.value = row.id
  try {
    const d = await http.get(`/grading/${row.id}`)
    if (!d || !d.grade) {
      throw new Error('记录不存在或已被删除')
    }
    gdGrade.value = d.grade
    gdDetail.value = d
    gdDetails.value = d.details || []
    // 指定题号（考生管理错题号跳转）优先定位；否则默认第一道错题
    let pick = null
    if (targetQNo) {
      pick = gdDetails.value.find(x => String(x.qNo) === String(targetQNo))
    }
    if (!pick) {
      pick = gdDetails.value.find(x => Number(x.gotScore) < Number(x.score))
    }
    if (pick) {
      gdPicked.value = pick
      await scrollToDetailRow(pick)
    }
  } catch {
    /* 拦截器提示 */
  } finally {
    gdLoading.value = false
  }
}

/** 弹窗表格滚动并高亮定位到指定题目行（弹窗渲染动画后执行；限定最后打开的弹窗，避免定位到页面表格） */
async function scrollToDetailRow(pick) {
  await nextTick()
  setTimeout(() => {
    try {
      const dialogs = document.querySelectorAll('.el-dialog')
      const dlg = dialogs.length ? dialogs[dialogs.length - 1] : null
      const wrap = dlg?.querySelector('.el-table__body-wrapper')
      if (!wrap) return
      const rows = wrap.querySelectorAll('tr.el-table__row')
      for (const tr of rows) {
        const no = tr.querySelector('td')?.innerText?.trim()
        if (no === String(pick.qNo)) {
          tr.scrollIntoView({ block: 'center' })
          break
        }
      }
    } catch { /* 定位失败不影响展示 */ }
  }, 400)
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
  // 从考生管理「错题号」链接跳转过来：带 gradeId 时直接打开该生答卷详情并定位到该题
  if (route.query.gradeId) {
    openGradeDetail({ id: route.query.gradeId }, route.query.qno)
    router.replace({ query: { ...route.query, gradeId: undefined, qno: undefined } })
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
.gd-dialog-head { display: flex; align-items: center; font-size: 16px; font-weight: 600; }
/* 错题详情弹窗 */
.wq-meta { display: flex; gap: 14px; align-items: center; margin-bottom: 12px; font-size: 13px; color: #606266; flex-wrap: wrap; }
.wq-meta b { color: #303133; }
.wq-rate { color: #F56C6C; font-size: 15px; }
/* 题干：正常模式与最大化模式的醒目程度不同 */
.wq-stem {
  margin-bottom: 10px; padding: 10px 14px; background: #f5f7fa;
  border-left: 4px solid #409EFF; border-radius: 4px;
  font-size: 15px; line-height: 1.8; white-space: pre-wrap; word-break: break-all; color: #303133;
}
.wq-stem.big {
  font-size: 22px; font-weight: 600; line-height: 2;
  padding: 18px 24px; background: #ecf5ff; border-left: 6px solid #409EFF;
}
/* 答案隐藏遮罩 */
.wq-answer-mask {
  margin-bottom: 8px; padding: 14px; text-align: center;
  background: repeating-linear-gradient(45deg, #fafafa, #fafafa 12px, #f5f5f5 12px, #f5f5f5 24px);
  border: 1px dashed #c0c4cc; border-radius: 4px;
  color: #909399; font-size: 13px; cursor: pointer; user-select: none;
}
/* 选择/判断题选项列表（错题详情 + 答卷详情共用） */
.wq-opts, .gd-opts {
  margin-bottom: 8px; padding: 8px 14px; background: #f8fbff;
  border: 1px solid #e4e7ed; border-radius: 4px;
  font-size: 14px; line-height: 2; color: #303133;
}
.wq-opts .wq-opt, .gd-opts span { margin-right: 16px; }
.wq-opts.big, .gd-opts.big {
  font-size: 20px; font-weight: 600; line-height: 2.2; padding: 14px 20px;
  background: #ecf5ff; border-color: #b3d8ff;
}
/* 标准答案：红色突出；最大化时加粗加大 */
.gd-ans { color: #c00000; font-weight: 700; font-size: 14px; margin-bottom: 8px; }
.wq-block.ans { color: #c00000; font-weight: 700; }
.gd-ans.big, .wq-block.ans.big { font-size: 20px; }
.wq-block {
  margin-bottom: 8px; padding: 8px 12px; background: #f5f7fa;
  border-left: 3px solid #409EFF; border-radius: 4px;
  font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-all;
}
:deep(.gd-wrong-row > td.el-table__cell) { background-color: #fef0f0 !important; }
:deep(.el-table__body tr.gd-wrong-row:hover > td.el-table__cell) { background-color: #fde2e2 !important; }
.gd-cell { margin-top: 14px; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.gd-cell-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.gd-cell-img { max-width: 100%; max-height: 320px; border: 1px solid #eee; border-radius: 4px; display: block; margin: 0 auto; }
.gd-stem { margin: 6px 0 10px; padding: 8px 12px; background: #f5f7fa; border-left: 3px solid #409EFF; border-radius: 4px; font-size: 14px; line-height: 1.7; text-align: left; }
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
