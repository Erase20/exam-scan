<template>
  <div class="m-page">
    <div class="m-head">
      <router-link to="/m" class="m-back">← 主页</router-link>
      <h2 class="m-title">📷 手机拍照阅卷</h2>
    </div>

    <div v-if="connOk === false" class="m-conn-warn">
      ⚠️ 连不上服务器（拍照上传会失败）。请确认手机与电脑连的是同一个 WiFi；
      如果用的是<b>百度/QQ/UC 等浏览器</b>，请到「设置 → 高级设置」关闭
      <b>「云加速 / 极速模式」</b>后刷新，或改用系统自带浏览器打开本页。
    </div>

    <!-- 试卷选择：URL 带 paperId 时锁定，否则现场选择 -->
    <div class="m-card">
      <template v-if="curPaper">
        <div class="m-paper-name">当前试卷</div>
        <div class="m-paper-title">{{ curPaper.title }}（{{ curPaper.totalScore }} 分）</div>
      </template>
      <template v-else>
        <div class="m-paper-name">选择试卷</div>
        <select v-model="selPaperId" class="m-select">
          <option v-for="p in papers" :key="p.id" :value="p.id">{{ p.title }}（{{ p.totalScore }} 分）</option>
        </select>
      </template>
    </div>

    <!-- 拍照 / 相册 -->
    <div class="m-card m-actions">
      <div class="m-mode">
        <span>阅卷模式</span>
        <select v-model="gradeMode" class="m-select" @change="saveGradeMode">
          <option value="ocr">全部 OCR（识别手写答案）</option>
          <option value="fast">快速截图（主观题不 OCR，更快）</option>
        </select>
      </div>
      <div style="font-size:11px;color:#909399;margin:0 0 8px">两种模式均识别姓名 / 班级 / 考号</div>
      <button type="button" class="m-shot" :class="{ disabled: !curPaperId || uploading }"
              :disabled="!curPaperId || uploading" @click="frameVisible = true">
        <span v-if="!uploading">📷 拍照录入（带取景线框）</span>
        <span v-else class="m-loading">⏳ 正在识别…</span>
      </button>
      <label class="m-album" :class="{ disabled: !curPaperId || uploading }">
        <input type="file" accept="image/*" hidden
               :disabled="!curPaperId || uploading" @change="e => onShot(e, false)" />
        从相册选择
      </label>
      <div class="m-page-merge">
        <span class="m-page-merge-state">
          {{ mergeIntoId == null ? '下一张照片将新建阅卷记录' : `下一张照片将合并到记录 #${mergeIntoId}（同一位学生的下一页）` }}
        </span>
        <button v-if="mergeIntoId != null" type="button" class="m-page-new"
                :disabled="uploading" @click="mergeIntoId = null">换一位学生（新建记录）</button>
      </div>
      <div v-if="previewUrl" class="m-preview">
        <img :src="previewUrl" alt="拍照结果预览" />
        <span v-if="uploading" class="m-loading">⏳ 正在识别…</span>
      </div>
      <div class="m-tip">
        拍照时会出现绿色取景线框：请正对答题卡，把整张答题卡（含四角黑色定位方块）放入框内；
        系统会自动识别四个定位点并校正裁切，无需手动调整，避免倾斜、反光和遮挡；提交后几秒内自动出分。
        <br />若拍照/上传一直失败：百度等浏览器请关闭「云加速」，或改用系统自带浏览器。
      </div>
    </div>

    <!-- 最新一次判分结果 -->
    <div v-if="result" class="m-card">
      <div class="m-card-head">
        <span>本次结果</span>
        <el-tag size="small" :type="result.status === 'CONFIRMED' ? 'success' : 'warning'">
          {{ result.status === 'CONFIRMED' ? '已确认' : '待人工确认主观题' }}
        </el-tag>
      </div>
      <div class="m-score">
        <span class="m-score-num">{{ result.totalScore }}</span>
        <span class="m-score-full">/ {{ result.fullScore }}</span>
      </div>
      <div class="m-sub">考号 {{ result.studentNo || '未识别' }} · 姓名 {{ result.studentName || '未识别' }} · 班级 {{ result.className || '未识别' }}</div>
      <div class="m-sub">客观题 {{ result.objectiveScore }} 分 · {{ result.mode === 'fast' ? '快速截图模式' : '全部 OCR 模式' }}</div>
      <div v-if="crops.length" class="m-crops">
        <div v-for="c in crops" :key="c.label" class="m-crop">
          <el-image :src="c.url" class="m-crop-img" fit="cover"
                    :preview-src-list="[c.url]" preview-teleported />
          <span>{{ c.label }}</span>
        </div>
      </div>
      <div class="m-counts">
        <span class="ok">对 {{ result.correct }}</span>
        <span class="bad">错 {{ result.wrong }}</span>
        <span class="muted">空白 {{ result.blank }}</span>
        <span class="warn">多涂 {{ result.multi }}</span>
      </div>
      <div class="m-detail">
        <div v-for="(d, i) in result.details" :key="i" class="m-q">
          <span class="m-q-no">{{ d.qNo }}</span>
          <el-tag size="small" :type="verdictType(d.verdict)">{{ verdictLabel(d.verdict) }}</el-tag>
          <span class="m-q-ans">
            答 <b>{{ d.studentAnswer || '—' }}</b> / 标准 <b>{{ d.standardAnswer || '—' }}</b>
          </span>
          <el-image v-if="d.cellCrop" :src="d.cellCrop" class="m-q-img" fit="cover"
                    :preview-src-list="[d.cellCrop]" preview-teleported />
          <span class="m-q-score">{{ d.verdict === 'MANUAL' ? '待人工' : `${d.gotScore}/${d.score}` }}</span>
        </div>
      </div>
    </div>

    <!-- 已提交记录（实时刷新） -->
    <div class="m-card">
      <div class="m-card-head">
        <span>已提交（{{ records.length }}）</span>
        <el-tag v-if="polling" size="small" type="success">实时刷新中</el-tag>
      </div>
      <div v-if="!records.length" class="m-muted">暂无记录，拍一张试试</div>
      <div v-for="r in records" :key="r.id" class="m-rec" @click="showRecord(r.id)">
        <span class="m-rec-no">{{ r.studentNo || '未识别考号' }}</span>
        <span class="m-rec-score">{{ r.totalScore }} / {{ r.fullScore }}</span>
        <span class="m-rec-time">{{ (r.createTime || '').slice(5, 16) }}</span>
      </div>
    </div>

    <PhotoFrame v-model="frameVisible" title="拍照阅卷" :aspect="0.707"
                hint="请正对答题卡，把整张答题卡（含四角黑色定位方块）放入绿色线框内，避免倾斜、反光和遮挡"
                @confirm="onFramedPhoto" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import { compressImage, checkConn, uploadErrMsg } from '../utils/mobile'
import PhotoFrame from '../components/PhotoFrame.vue'

const route = useRoute()
const papers = ref([])
const selPaperId = ref(null)
const uploading = ref(false)
const result = ref(null)
/** 多页答题卡：非空时下一张照片合并到该阅卷记录（同一位学生的第 2 页及以后） */
const mergeIntoId = ref(null)
const records = ref([])
const polling = ref(false)
const connOk = ref(null)      // null=检测中 true=正常 false=连不上
const previewUrl = ref(null)  // 拍照后的本地预览
const frameVisible = ref(false)
const gradeMode = ref(localStorage.getItem('examGradeMode') || 'ocr')
const saveGradeMode = () => localStorage.setItem('examGradeMode', gradeMode.value)
let pollTimer = null

// 识别不出时的完整截图入口（点缩略图可放大人工辨认）
const crops = computed(() => {
  const r = result.value
  if (!r) return []
  return [
    { label: '信息区整图', url: r.infoCrop },
    { label: '姓名', url: r.nameCrop },
    { label: '班级', url: r.classCrop },
    { label: '学号手写', url: r.idCrop },
  ].filter(c => c.url)
})

// URL 带 paperId 时锁定试卷；否则从试卷列表里选
const urlPaperId = route.query.paperId ? Number(route.query.paperId) : null
const curPaperId = computed(() => urlPaperId || selPaperId.value)
const curPaper = computed(() => papers.value.find(p => Number(p.id) === Number(curPaperId.value)))

const verdictLabel = v => ({ CORRECT: '正确', WRONG: '错误', BLANK: '未作答', MULTI: '多涂', MANUAL: '待人工' }[v] || v)
const verdictType = v => ({ CORRECT: 'success', WRONG: 'danger', BLANK: 'info', MULTI: 'warning', MANUAL: 'warning' }[v] || 'info')

/** 手机照片先压缩再上传（公共实现见 utils/mobile.js） */

async function onShot(e, fromCamera) {
  const raw = e.target.files?.[0]
  e.target.value = ''
  await handlePhoto(raw, fromCamera)
}

/** 取景线框组件回传的是已按框对齐/裁切的照片 */
function onFramedPhoto(file) {
  handlePhoto(file, true)
}

async function handlePhoto(raw, fromCamera) {
  if (!raw) {
    ElMessage.warning('未获取到照片，请重试或改用「从相册选择」')
    return
  }
  if (!curPaperId.value) { ElMessage.warning('请先选择试卷'); return }
  uploading.value = true
  try {
    const file = await compressImage(raw)
    // 预览用压缩后的标准 JPEG（拍照原始文件可能缺 MIME 类型导致 <img> 显示不了）
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(file)
    const fd = new FormData()
    fd.append('file', file)
    let url = `/grading/grade?paperId=${curPaperId.value}&mode=${gradeMode.value}`
    // 多页答题卡：点过「继续拍下一页」后，后续照片合并到同一条阅卷记录
    if (mergeIntoId.value != null) url += `&mergeInto=${mergeIntoId.value}`
    const r = await http.post(url, fd,
      { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 })
    result.value = { ...r, status: 'PENDING' }
    mergeIntoId.value = r.gradeId
    ElMessage.success(`识别完成：总分 ${r.totalScore} / ${r.fullScore}` + (r.details?.length ? `，共 ${r.details.length} 题` : ''))
    loadRecords()
  } catch (err) {
    ElMessage.error((fromCamera ? '识别失败：' : '上传失败：') + uploadErrMsg(err))
  } finally {
    uploading.value = false
  }
}

async function showRecord(id) {
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

async function loadRecords() {
  if (!curPaperId.value) return
  try {
    const d = await http.get(`/grading/list?paperId=${curPaperId.value}`)
    records.value = d || []
    polling.value = true
  } catch (e) {}
}

onMounted(async () => {
  checkConn().then(ok => { connOk.value = ok })
  papers.value = await http.get('/paper/list').catch(() => [])
  if (!urlPaperId && papers.value.length) selPaperId.value = papers.value[0].id
  await loadRecords()
  pollTimer = setInterval(loadRecords, 3000)   // 实时刷新已提交记录
})

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<style scoped>
.m-page { max-width: 560px; margin: 0 auto; padding: 14px 12px 40px; }
.m-title { text-align: center; font-size: 20px; margin: 6px 0 14px; }
.m-head { position: relative; display: flex; align-items: center; justify-content: center; min-height: 38px; }
.m-head .m-title { margin: 0; font-size: 19px; }
.m-back {
  position: absolute; left: 0; display: inline-flex; align-items: center;
  min-height: 34px; padding: 0 12px; border-radius: 18px;
  background: #ecf5ff; border: 1px solid #b3d8ff; color: #409eff;
  font-size: 13px; text-decoration: none; white-space: nowrap;
}
.m-card { background: #fff; border-radius: 12px; padding: 14px; margin-bottom: 12px; box-shadow: 0 1px 4px rgba(0,0,0,.06); }
.m-paper-name { font-size: 12px; color: #909399; margin-bottom: 4px; }
.m-paper-title { font-size: 15px; font-weight: 600; }
.m-select { width: 100%; padding: 10px; font-size: 15px; border: 1px solid #dcdfe6; border-radius: 8px; }
.m-actions { text-align: center; }
.m-shot, .m-album {
  display: block; width: 100%; box-sizing: border-box; padding: 15px 0; margin-bottom: 10px;
  border: 0; border-radius: 10px; font-size: 17px; font-family: inherit; font-weight: 600;
  text-align: center; cursor: pointer; user-select: none;
}
.m-shot { background: #409eff; color: #fff; }
.m-shot.disabled, .m-album.disabled { opacity: .5; pointer-events: none; }
.m-album { background: #f0f2f5; color: #409eff; }
.m-page-merge { display: flex; flex-direction: column; gap: 6px; margin: 4px 0 10px; }
.m-page-merge-state { font-size: 12px; color: #67c23a; }
.m-page-new { border: 1px solid #dcdfe6; background: #fff; border-radius: 8px; padding: 9px 0; font-size: 14px; font-family: inherit; color: #606266; cursor: pointer; }
.m-page-new:disabled { opacity: .5; }
.m-loading { animation: blink 1.2s infinite; }
@keyframes blink { 50% { opacity: .55; } }
.m-tip { font-size: 12px; color: #909399; line-height: 1.6; }
.m-conn-warn {
  background: #fef0f0; border: 1px solid #fbc4c4; color: #c45656;
  border-radius: 10px; padding: 10px 12px; font-size: 13px; line-height: 1.7; margin-bottom: 12px;
}
.m-preview { text-align: center; margin-bottom: 10px; }
.m-preview img { max-width: 100%; max-height: 220px; border-radius: 8px; border: 1px solid #eee; }
.m-preview .m-loading { display: block; font-size: 13px; color: #409eff; margin-top: 4px; }
.m-card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; font-size: 14px; margin-bottom: 8px; }
.m-score { text-align: center; }
.m-score-num { font-size: 44px; font-weight: 700; color: #409eff; }
.m-score-full { font-size: 16px; color: #909399; margin-left: 6px; }
.m-sub { text-align: center; font-size: 12px; color: #606266; margin: 4px 0 8px; }
.m-counts { display: flex; justify-content: center; gap: 14px; font-size: 13px; margin-bottom: 10px; }
.ok { color: #67c23a; } .bad { color: #f56c6c; } .warn { color: #e6a23c; } .muted { color: #909399; }
.m-detail { border-top: 1px dashed #eee; padding-top: 6px; max-height: 300px; overflow-y: auto; }
.m-q { display: flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 12px; border-bottom: 1px dashed #f5f5f5; }
.m-q-no { width: 28px; text-align: center; color: #909399; }
.m-q-ans { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #606266; }
.m-q-score { color: #303133; font-weight: 600; flex-shrink: 0; }
.m-q-img { width: 36px; height: 24px; border-radius: 4px; border: 1px solid #eee; flex-shrink: 0; }
.m-mode { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #606266; margin-bottom: 10px; }
.m-mode .m-select { flex: 1; min-width: 0; }
.m-crops { display: flex; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
.m-crop { display: flex; flex-direction: column; align-items: center; gap: 2px; font-size: 11px; color: #909399; }
.m-crop-img { width: 66px; height: 44px; border: 1px solid #eee; border-radius: 4px; }
.m-muted { font-size: 12px; color: #909399; text-align: center; padding: 8px 0; }
.m-rec { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px dashed #f0f0f0; font-size: 13px; cursor: pointer; }
.m-rec-no { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-rec-score { font-weight: 600; color: #409eff; }
.m-rec-time { color: #c0c4cc; font-size: 11px; }
</style>
