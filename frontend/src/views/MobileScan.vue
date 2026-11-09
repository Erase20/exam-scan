<template>
  <div class="m-page">
    <div class="m-head">
      <router-link to="/m" class="m-back">← 主页</router-link>
      <h2 class="m-title">🧾 拍照传卷</h2>
    </div>

    <div v-if="connOk === false" class="m-conn-warn">
      ⚠️ 连不上服务器（上传会失败）。请确认手机与电脑连的是同一个 WiFi；
      如果用的是<b>百度/QQ/UC 等浏览器</b>，请到「设置 → 高级设置」关闭
      <b>「云加速 / 极速模式」</b>后刷新，或改用系统自带浏览器打开本页。
    </div>

    <div class="m-card">
      <div class="m-field">
        <span class="m-label">试卷名称</span>
        <input v-model="docName" class="m-input" placeholder="留空自动按时间命名" />
      </div>
      <div class="m-field-row">
        <div class="m-field">
          <span class="m-label">学科</span>
          <select v-model="subject" class="m-select">
            <option v-for="s in subjects" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="m-field">
          <span class="m-label">年级</span>
          <select v-model="grade" class="m-select">
            <option v-for="g in grades" :key="g" :value="g">{{ g }}</option>
          </select>
        </div>
      </div>

      <button type="button" class="m-shot" :class="{ disabled: uploading }"
              :disabled="uploading" @click="frameVisible = true">
        <span v-if="!uploading">📷 拍照添加（带取景线框）</span>
        <span v-else class="m-loading">⏳ 正在上传…</span>
      </button>
      <label class="m-album" :class="{ disabled: uploading }">
        <input type="file" accept="image/*" multiple hidden :disabled="uploading" @change="e => onPick(e, false)" />
        从相册选择（可多张）
      </label>

      <div v-if="pending.length" class="m-pending">
        已选 {{ pending.length }} 张，共 {{ (totalKb / 1024).toFixed(1) }} MB
        <el-button size="small" type="primary" :loading="uploading" style="margin-left:8px" @click="doUpload">
          全部上传
        </el-button>
        <el-button size="small" @click="pending = []">清空</el-button>
      </div>

      <div class="m-tip">
        逐页拍试卷，每页一张；拍照时会出现绿色取景线框，把整页四边放入框内，
        系统会尽量按四角定位点自动校正，无需手动裁切。
        上传后电脑端「扫描识别」的历史文档里会直接显示原图，点「打开原图」即可框选题目区域识别入库。
      </div>
    </div>

    <div class="m-card">
      <div class="m-card-head">最近上传（{{ docs.length }}）</div>
      <div v-if="!docs.length" class="m-muted">还没有上传过，拍一张试试</div>
      <div v-for="d in docs" :key="d.id" class="m-rec">
        <span class="m-rec-name">{{ d.docName }}</span>
        <span class="m-rec-meta">{{ d.pageCount }}页 · {{ (d.createTime || '').slice(5, 16) }}</span>
      </div>
    </div>

    <PhotoFrame v-model="frameVisible" title="拍照传卷" :aspect="0.707" :require-markers="false"
                hint="请正对试卷页面，把整页四边放入绿色线框内，避免倾斜、阴影和反光"
                @confirm="onFramedPhoto" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { compressImage, stamp, checkConn, uploadErrMsg } from '../utils/mobile'
import PhotoFrame from '../components/PhotoFrame.vue'

const subjects = ['数学', '语文', '英语', '物理', '化学', '科学']
const grades = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '七年级', '八年级', '九年级']

const docName = ref(''); const subject = ref('科学'); const grade = ref('六年级')
const pending = ref([])      // 待上传的压缩后 File
const uploading = ref(false)
const docs = ref([])
const connOk = ref(null)     // null=检测中 true=正常 false=连不上
const frameVisible = ref(false)

const totalKb = computed(() => pending.value.reduce((s, f) => s + f.size, 0) / 1024)

async function onPick(e, fromCamera) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  await addFiles(files, fromCamera)
}

/** 取景线框组件回传的是已按框对齐/裁切的照片 */
function onFramedPhoto(file) {
  addFiles([file], true)
}

async function addFiles(files, fromCamera) {
  if (!files.length) return
  try {
    const compressed = []
    for (const f of files) compressed.push(await compressImage(f))
    pending.value = [...pending.value, ...compressed]
    if (!docName.value) docName.value = `手机拍摄-${stamp()}`
    ElMessage.success(`已添加 ${compressed.length} 张，点「全部上传」提交`)
  } catch (err) {
    ElMessage.error((fromCamera ? '拍照' : '选图') + '失败：' + uploadErrMsg(err))
  }
}

/** 每次上传建一个文档；多张一次传给 /scan/upload 作为多页 */
async function doUpload() {
  if (!pending.value.length) return
  uploading.value = true
  try {
    const fd = new FormData()
    pending.value.forEach(f => fd.append('files', f))
    fd.append('docName', docName.value || `手机拍摄-${stamp()}`)
    fd.append('subject', subject.value)
    fd.append('grade', grade.value)
    const doc = await http.post('/scan/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    ElMessage.success(`已上传「${doc.docName || docName.value}」共 ${pending.value.length} 页，电脑端「扫描识别」历史文档点「打开原图」即可查看`)
    pending.value = []
    docName.value = ''
    loadDocs()
  } catch (e) {
    ElMessage.error('上传失败：' + uploadErrMsg(e))
  } finally {
    uploading.value = false
  }
}

async function loadDocs() {
  try {
    const d = await http.get('/scan/docs?page=1&size=10')
    docs.value = d.records || []
  } catch (e) {}
}

onMounted(() => {
  checkConn().then(ok => { connOk.value = ok })
  loadDocs()
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
.m-field { flex: 1; margin-bottom: 10px; }
.m-field-row { display: flex; gap: 10px; }
.m-label { display: block; font-size: 12px; color: #909399; margin-bottom: 4px; }
.m-input, .m-select {
  width: 100%; box-sizing: border-box; padding: 10px; font-size: 14px;
  border: 1px solid #dcdfe6; border-radius: 8px; background: #fff;
}
.m-shot, .m-album {
  display: block; width: 100%; box-sizing: border-box; padding: 15px 0; margin-bottom: 10px;
  border: 0; border-radius: 10px; font-size: 16px; font-family: inherit; font-weight: 600;
  text-align: center; cursor: pointer; user-select: none;
}
.m-shot { background: #409eff; color: #fff; }
.m-shot.disabled, .m-album.disabled { opacity: .5; pointer-events: none; }
.m-album { background: #f0f2f5; color: #409eff; }
.m-loading { animation: blink 1.2s infinite; }
@keyframes blink { 50% { opacity: .55; } }
.m-pending {
  background: #ecf5ff; border-radius: 8px; padding: 10px; font-size: 13px;
  color: #409eff; margin-bottom: 10px; display: flex; align-items: center; flex-wrap: wrap;
}
.m-tip { font-size: 12px; color: #909399; line-height: 1.6; }
.m-conn-warn {
  background: #fef0f0; border: 1px solid #fbc4c4; color: #c45656;
  border-radius: 10px; padding: 10px 12px; font-size: 13px; line-height: 1.7; margin-bottom: 12px;
}
.m-card-head { font-weight: 600; font-size: 14px; margin-bottom: 6px; }
.m-muted { font-size: 12px; color: #909399; text-align: center; padding: 8px 0; }
.m-rec { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px dashed #f0f0f0; font-size: 13px; }
.m-rec-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-rec-meta { color: #c0c4cc; font-size: 11px; flex-shrink: 0; }
</style>
