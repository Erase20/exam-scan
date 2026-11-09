<template>
  <div v-if="modelValue" class="pf-overlay">
    <div class="pf-head">
      <span class="pf-title">{{ title }}</span>
      <button class="pf-close" type="button" @click="close">✕ 关闭</button>
    </div>

    <!-- 实时相机：周期检测四角定位点，锁定后拍摄即自动透视校正 -->
    <div v-if="stage === 'camera'" class="pf-body">
      <div class="pf-cam">
        <video ref="videoEl" autoplay playsinline muted></video>
        <div class="pf-cam-frame" :class="{ locked: !!markers }" :style="{ aspectRatio: String(aspect) }">
          <span class="pf-corner tl"></span><span class="pf-corner tr"></span>
          <span class="pf-corner bl"></span><span class="pf-corner br"></span>
        </div>
        <span v-for="(p, i) in markerDots" :key="i" class="pf-dot" :style="p"></span>
        <div class="pf-cam-hint" :class="{ locked: !!markers }">
          {{ markers ? '✓ 已锁定四角定位点，拍摄后将自动校正裁切' : hint }}
        </div>
      </div>
    </div>

    <!-- 系统相机降级：HTTP 内网等无法使用网页摄像头时，拍完仍自动按点位裁切 -->
    <div v-else-if="stage === 'fallback'" class="pf-body pf-fallback">
      <div class="pf-guide">
        <div class="pf-guide-frame" :style="{ aspectRatio: String(aspect) }">
          <span class="pf-corner tl"></span><span class="pf-corner tr"></span>
          <span class="pf-corner bl"></span><span class="pf-corner br"></span>
        </div>
      </div>
      <div class="pf-hint">{{ hint }}</div>
      <div class="pf-note">
        当前浏览器不支持网页实时取景（内网 HTTP 环境常见）。用系统相机拍下整页后，
        系统会自动识别四角定位点并校正裁切，无需手动拖框。
      </div>
    </div>

    <!-- 自动处理 / 未识别到点位 -->
    <div v-else class="pf-body pf-adjust">
      <div ref="stageEl" class="pf-stage" :style="stageStyle">
        <img :src="photoUrl" class="pf-photo" alt="照片" draggable="false" />
        <div class="pf-fixed-frame">
          <span class="pf-corner tl"></span><span class="pf-corner tr"></span>
          <span class="pf-corner bl"></span><span class="pf-corner br"></span>
        </div>
      </div>
      <div v-if="stage === 'processing'" class="pf-status">⏳ 正在按四角定位点自动校正并裁切…</div>
      <template v-else>
        <div class="pf-warn">⚠️ 未识别到四角定位点，请让整张答题卡完整进入线框、避免反光和遮挡后重拍</div>
        <div v-if="qualityWarn" class="pf-warn">⚠️ {{ qualityWarn }}</div>
        <div class="pf-hint">也可直接使用整图（不做校正），但识别准确率可能下降。</div>
      </template>
    </div>

    <div class="pf-bar">
      <template v-if="stage === 'camera'">
        <button class="pf-btn primary" type="button" @click="captureFrame">📸 拍摄（自动裁切）</button>
        <button class="pf-btn" type="button" @click="toFallback">改用系统相机</button>
      </template>
      <template v-else-if="stage === 'fallback'">
        <button class="pf-btn primary" type="button" @click="openSystemCamera">📷 拍照（自动裁切）</button>
        <button class="pf-btn" type="button" @click="openAlbum">从相册选择</button>
      </template>
      <template v-else-if="stage === 'processing'">
        <button class="pf-btn" type="button" @click="close">取消</button>
      </template>
      <template v-else>
        <button class="pf-btn primary" type="button" @click="resetCapture">重拍</button>
        <button class="pf-btn" type="button" @click="retryAuto">重新检测</button>
        <button class="pf-btn" type="button" @click="useWhole">使用整图</button>
      </template>
    </div>

    <input ref="camInput" type="file" accept="image/*" capture="environment" hidden @change="onFilePicked" />
    <input ref="albumInput" type="file" accept="image/*" hidden @change="onFilePicked" />
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '拍照' },
  hint: { type: String, default: '请将整张页面放入线框内，四角定位点不要被遮挡，尽量正对、避免倾斜和反光' },
  // 取景框宽高比，A4 竖版约 0.707
  aspect: { type: Number, default: 0.707 },
  // false 表示没有定位点也自动使用整图（拍照传卷等普通试卷场景）
  requireMarkers: { type: Boolean, default: true },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const stage = ref('fallback')           // camera | fallback | processing | failed
const videoEl = ref(null)
const stageEl = ref(null)
const camInput = ref(null)
const albumInput = ref(null)
const photoUrl = ref(null)
const photoFile = ref(null)
const photoSize = ref(null)
const stagePx = ref({ w: 320, h: 452 })
const qualityWarn = ref('')
const markers = ref(null)
const markerDots = ref([])

let stream = null
let cameraSeq = 0
let detectTimer = null
let detecting = false

const visible = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v),
})
const stageStyle = computed(() => ({
  width: `${stagePx.value.w}px`,
  height: `${stagePx.value.h}px`,
}))

function close() { visible.value = false }

function stopStream() {
  stream?.getTracks().forEach(t => t.stop())
  stream = null
}
function stopDetect() {
  if (detectTimer) clearInterval(detectTimer)
  detectTimer = null
  detecting = false
}

function cleanup() {
  cameraSeq++
  stopDetect()
  stopStream()
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = null
  photoFile.value = null
  photoSize.value = null
  qualityWarn.value = ''
  markers.value = null
  markerDots.value = []
  stage.value = 'fallback'
}

watch(() => props.modelValue, async v => {
  if (v) {
    cleanup()
    await openCamera()
  } else {
    cleanup()
  }
})

/** 优先用网页相机实时取景；HTTP 内网/无权限时自动降级为系统相机 */
async function openCamera() {
  const seq = ++cameraSeq
  if (!navigator.mediaDevices?.getUserMedia) {
    stage.value = 'fallback'
    return
  }
  try {
    // 权限弹窗一直不响应时不能让用户卡住，4 秒后直接降级为系统相机
    const s = await Promise.race([
      navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 2560 } },
      }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('相机启动超时')), 4000)),
    ])
    if (seq !== cameraSeq) {
      s.getTracks().forEach(t => t.stop())
      return
    }
    stream = s
    stage.value = 'camera'
    await nextTick()
    if (videoEl.value) {
      videoEl.value.srcObject = stream
      await videoEl.value.play().catch(() => {})
    }
    startDetect()
  } catch (e) {
    if (seq !== cameraSeq) return
    stopStream()
    stage.value = 'fallback'
  }
}

/** 实时取景时周期检测四角定位点，线框上打点显示锁定状态 */
function startDetect() {
  stopDetect()
  detectTimer = setInterval(doDetect, 1500)
  doDetect()
}

async function doDetect() {
  if (detecting || stage.value !== 'camera') return
  const v = videoEl.value
  if (!v || !v.videoWidth) return
  detecting = true
  try {
    const scale = Math.min(1, 700 / Math.max(v.videoWidth, v.videoHeight))
    const c = document.createElement('canvas')
    c.width = Math.max(1, Math.round(v.videoWidth * scale))
    c.height = Math.max(1, Math.round(v.videoHeight * scale))
    c.getContext('2d').drawImage(v, 0, 0, c.width, c.height)
    const blob = await new Promise(r => c.toBlob(r, 'image/jpeg', 0.7))
    if (!blob) return
    const fd = new FormData()
    fd.append('file', blob, 'frame.jpg')
    const resp = await fetch('/api/camera/detect', { method: 'POST', body: fd })
    const j = await resp.json()
    const pts = j?.data?.found ? j.data.points : null
    markers.value = pts
    if (pts) {
      // video 是 object-fit: contain，需把帧内坐标映射到实际显示区域（含留白偏移）
      const cw = v.clientWidth || v.videoWidth
      const ch = v.clientHeight || v.videoHeight
      const scale = Math.min(cw / c.width, ch / c.height)
      const dw = c.width * scale, dh = c.height * scale
      const ox = (cw - dw) / 2, oy = (ch - dh) / 2
      markerDots.value = pts.map(p => ({
        left: `${((ox + (p.x / c.width) * dw) / cw) * 100}%`,
        top: `${((oy + (p.y / c.height) * dh) / ch) * 100}%`,
      }))
    } else {
      markerDots.value = []
    }
  } catch (e) {
    markers.value = null
    markerDots.value = []
  } finally {
    detecting = false
  }
}

function toFallback() {
  stopDetect()
  stopStream()
  stage.value = 'fallback'
}

function openSystemCamera() { camInput.value?.click() }
function openAlbum() { albumInput.value?.click() }

function captureFrame() {
  const v = videoEl.value
  if (!v || !v.videoWidth) return
  const c = document.createElement('canvas')
  c.width = v.videoWidth
  c.height = v.videoHeight
  c.getContext('2d').drawImage(v, 0, 0)
  c.toBlob(b => {
    if (b) usePhoto(new File([b], `camera-${Date.now()}.jpg`, { type: 'image/jpeg' }))
  }, 'image/jpeg', 0.92)
}

function onFilePicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (file) usePhoto(file)
}

/** 按屏幕可用空间等比缩放预览区 */
function fitStage(w, h) {
  const ca = w / h
  const maxW = Math.max(160, window.innerWidth - 16)
  const maxH = Math.max(180, window.innerHeight - 210)
  let sw = Math.min(maxW, maxH * ca)
  let sh = sw / ca
  if (sh > maxH) { sh = maxH; sw = sh * ca }
  stagePx.value = { w: Math.round(sw), h: Math.round(sh) }
}
function onResize() {
  if (photoSize.value) fitStage(photoSize.value.w, photoSize.value.h)
}
onMounted(() => window.addEventListener('resize', onResize))

/** 亮度/对比度粗检，仅在未识别到点位时用于提示重拍 */
function checkQuality(img) {
  try {
    const c = document.createElement('canvas')
    c.width = 64; c.height = 64
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0, 64, 64)
    const d = ctx.getImageData(0, 0, 64, 64).data
    let sum = 0, sum2 = 0
    const n = 64 * 64
    for (let i = 0; i < d.length; i += 4) {
      const g = (d[i] + d[i + 1] + d[i + 2]) / 3
      sum += g; sum2 += g * g
    }
    const mean = sum / n
    const variance = sum2 / n - mean * mean
    const msgs = []
    if (mean < 55) msgs.push('画面偏暗')
    if (mean > 210) msgs.push('画面过亮或有反光')
    if (variance < 100) msgs.push('画面对比度偏低，可能模糊')
    qualityWarn.value = msgs.length ? `${msgs.join('、')}，建议在光线充足处重拍` : ''
  } catch (e) {
    qualityWarn.value = ''
  }
}

/** 调用后端按四角定位点自动透视校正，返回可直接上传的 JPEG */
async function autoCrop(file) {
  const fd = new FormData()
  fd.append('file', file, file.name || 'photo.jpg')
  const resp = await fetch('/api/camera/auto-crop', { method: 'POST', body: fd })
  if (!resp.ok) return null
  const blob = await resp.blob()
  if (!blob || !blob.size) return null
  return new File([blob], `auto-crop-${Date.now()}.jpg`, { type: 'image/jpeg' })
}

/** 拍照/选图后全自动处理：成功直接回传，不出现任何手动裁切界面 */
async function usePhoto(file) {
  stopDetect()
  stopStream()
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = URL.createObjectURL(file)
  photoFile.value = file
  const img = new Image()
  img.onload = async () => {
    photoSize.value = { w: img.naturalWidth, h: img.naturalHeight }
    fitStage(img.naturalWidth, img.naturalHeight)
    checkQuality(img)
    stage.value = 'processing'
    try {
      const cropped = await autoCrop(file)
      if (cropped) {
        ElMessage.success('已按四角定位点自动校正裁切')
        emit('confirm', cropped)
        close()
        return
      }
    } catch (e) { /* 网络异常按下面兜底 */ }
    if (!props.requireMarkers) {
      emit('confirm', file)   // 普通试卷没有定位点，自动使用整图
      close()
      return
    }
    stage.value = 'failed'
  }
  img.onerror = () => { stage.value = 'failed' }
  img.src = photoUrl.value
}

function retryAuto() {
  if (photoFile.value) usePhoto(photoFile.value)
}

function useWhole() {
  if (photoFile.value) emit('confirm', photoFile.value)
  close()
}

function resetCapture() {
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = null
  photoFile.value = null
  photoSize.value = null
  qualityWarn.value = ''
  openCamera()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  cleanup()
})
</script>

<style scoped>
.pf-overlay {
  position: fixed; inset: 0; z-index: 3000; background: #000; color: #fff;
  display: flex; flex-direction: column;
}
.pf-head {
  flex-shrink: 0; display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px; font-size: 15px; background: rgba(0, 0, 0, .85);
}
.pf-title { font-weight: 600; }
.pf-close, .pf-btn {
  border: 0; border-radius: 8px; font-size: 14px; font-family: inherit; cursor: pointer;
  background: rgba(255, 255, 255, .16); color: #fff; padding: 9px 14px;
}
.pf-body { flex: 1; min-height: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; }
.pf-cam { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
.pf-cam video { width: 100%; height: 100%; object-fit: contain; }
.pf-cam-frame {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  height: 82%; max-width: 86%; border: 1px solid rgba(255, 255, 255, .7);
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, .45);
}
.pf-cam-frame.locked { border-color: #4cd964; }
.pf-cam-hint, .pf-hint {
  position: absolute; bottom: 10px; left: 10px; right: 10px; text-align: center;
  font-size: 12px; line-height: 1.6; color: #fff; text-shadow: 0 1px 3px #000;
}
.pf-cam-hint.locked { color: #4cd964; font-weight: 600; }
.pf-dot {
  position: absolute; width: 12px; height: 12px; margin: -6px 0 0 -6px;
  border-radius: 50%; background: #4cd964; box-shadow: 0 0 0 2px #fff;
}
.pf-fallback { position: relative; padding: 12px; }
.pf-hint { position: static; margin-top: 10px; text-shadow: none; }
.pf-guide { width: 100%; flex: 1; min-height: 180px; display: flex; align-items: center; justify-content: center; }
.pf-guide-frame {
  position: relative; height: 74%; max-width: 80%; border: 2px dashed rgba(255, 255, 255, .85);
  border-radius: 4px; background: rgba(255, 255, 255, .05);
}
.pf-note { font-size: 12px; line-height: 1.7; color: #d0d0d0; margin-top: 6px; text-align: center; }
.pf-corner { position: absolute; width: 26px; height: 26px; border: 3px solid #4cd964; }
.pf-corner.tl { left: -3px; top: -3px; border-right: 0; border-bottom: 0; }
.pf-corner.tr { right: -3px; top: -3px; border-left: 0; border-bottom: 0; }
.pf-corner.bl { left: -3px; bottom: -3px; border-right: 0; border-top: 0; }
.pf-corner.br { right: -3px; bottom: -3px; border-left: 0; border-top: 0; }
.pf-adjust { position: relative; padding: 8px 8px 0; }
.pf-stage { position: relative; background: #111; margin: 0 auto; overflow: hidden; }
.pf-photo { display: block; width: 100%; height: 100%; }
.pf-fixed-frame {
  position: absolute; left: 7.5%; top: 5%; width: 85%; height: 90%;
  border: 1px dashed rgba(255, 255, 255, .55); pointer-events: none;
}
.pf-status { font-size: 13px; color: #4cd964; margin-top: 10px; text-align: center; }
.pf-warn { font-size: 12px; color: #ffd591; background: rgba(250, 140, 22, .18); padding: 6px 8px; border-radius: 6px; margin-top: 6px; }
.pf-adjust .pf-hint { color: #ddd; }
.pf-bar {
  flex-shrink: 0; display: flex; gap: 8px; flex-wrap: wrap; justify-content: center;
  padding: 10px; background: rgba(0, 0, 0, .9);
}
.pf-btn.primary { background: #409eff; font-weight: 600; }
</style>
