<template>
  <div>
    <h2 class="page-title">工作台</h2>
    <div class="stat-grid">
      <div class="stat-card"><div class="num">{{ stats.total || 0 }}</div><div class="label">题库总题数</div></div>
      <div class="stat-card"><div class="num">{{ stats.withImage || 0 }}</div><div class="label">含图题目</div></div>
      <div class="stat-card"><div class="num">{{ papers.length }}</div><div class="label">已组试卷</div></div>
      <div class="stat-card"><div class="num">{{ ocrOk ? '在线' : '离线' }}</div><div class="label">OCR 服务状态</div></div>
    </div>
    <div class="page-card" style="margin-top:16px">
      <h3 style="margin-top:0">题型分布</h3>
      <el-tag v-for="(c, t) in stats.byType || {}" :key="t" style="margin:4px" size="large">
        {{ typeLabel(t) }}：{{ c }} 题
      </el-tag>
      <el-empty v-if="!stats.total" description="题库为空，请先到「扫描识别」上传试卷" />
    </div>
    <div class="page-card">
      <h3 style="margin-top:0">功能导航</h3>
      <el-space wrap>
        <el-button type="primary" @click="$router.push('/scan')">📷 扫描试卷</el-button>
        <el-button type="success" @click="$router.push('/questions')">🔍 搜题</el-button>
        <el-button type="warning" @click="$router.push('/papers')">📝 智能组卷</el-button>
        <el-button @click="$router.push('/sheets')">🗒 生成答题卡</el-button>
        <el-button @click="$router.push('/grading')">✅ 扫描阅卷</el-button>
      </el-space>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import http, { Q_TYPE_LABELS } from '../api'
const stats = ref({}); const papers = ref([]); const ocrOk = ref(false)
const typeLabel = t => Q_TYPE_LABELS[t] || t
onMounted(async () => {
  stats.value = await http.post('/question/search', { page: 1, size: 1 }).then(d => ({ total: d.total, byType: (window.__st = null) && {} })).catch(() => ({}))
  try { stats.value = await http.get('/question/stats') } catch (e) {}
  try { papers.value = await http.get('/paper/list') } catch (e) {}
  try { ocrOk.value = (await http.get('/scan/health')).ocrAvailable } catch (e) {}
})
</script>
