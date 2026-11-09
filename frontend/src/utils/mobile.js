/** 手机端公共工具：移动端判断、图片压缩、文档命名 */

export function isMobile() {
  return window.innerWidth <= 768
}

/** 手机拍摄/截图压缩：最长边 maxSide，JPEG 质量 0.85（约 8MB 压到 500KB 内） */
export function compressImage(file, maxSide = 2000) {
  return new Promise((resolve, reject) => {
    // 部分安卓浏览器（百度/QQ等）拍照返回的 File 无 MIME 类型，<img> 解码不了：
    // 统一包装成标准 image/jpeg 的 Blob，预览与压缩都能用
    if (!file.type) {
      try {
        file = new File([file], file.name || 'photo.jpg', { type: 'image/jpeg' })
      } catch (e) {
        file = new Blob([file], { type: 'image/jpeg' })
      }
    }
    const url = URL.createObjectURL(file)
    const tryLoad = (src, onFail) => {
      const img = new Image()
      img.onload = () => onLoaded(img, src)
      img.onerror = () => { URL.revokeObjectURL(src); onFail ? onFail() : onFailLoad() }
      img.src = src
    }
    const onLoaded = (img, src) => {
      URL.revokeObjectURL(src)
      const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight))
      const c = document.createElement('canvas')
      c.width = Math.max(1, Math.round(img.naturalWidth * scale))
      c.height = Math.max(1, Math.round(img.naturalHeight * scale))
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
      c.toBlob(b => (b ? resolve(new File([b], 'photo.jpg', { type: 'image/jpeg' }))
                        : resolve(file)), 'image/jpeg', 0.85)
    }
    const onFailLoad = () => {
      // 浏览器能识别就原图直传（压缩失败不影响主流程）；识别不了多为 HEIC 格式
      if (/image\/(jpeg|jpg|png)/i.test(file.type)) {
        resolve(file)
      } else {
        reject(new Error('手机无法读取该照片格式（可能是 HEIC），请到相机设置把格式改为「兼容性最佳」后再拍'))
      }
    }
    // 先走 blob URL；失败再试 dataURL（个别浏览器 blob URL 加载异常）
    tryLoad(url, () => {
      const fr = new FileReader()
      fr.onload = () => tryLoad(fr.result, null)
      fr.onerror = onFailLoad
      fr.readAsDataURL(file)
    })
  })
}

/** 连接自检：能否连通后端（用于「云加速代理劫持内网请求」等场景的明确提示） */
export async function checkConn(timeoutMs = 6000) {
  try {
    const c = new AbortController()
    const t = setTimeout(() => c.abort(), timeoutMs)
    const r = await fetch('/api/scan/health', { signal: c.signal, cache: 'no-store' })
    clearTimeout(t)
    return r.ok
  } catch (e) {
    return false
  }
}

/** 上传/网络错误转成用户能看懂的提示（重点覆盖云加速代理与跨网段问题） */
export function uploadErrMsg(err) {
  const m = (err && err.message ? String(err.message) : String(err || '未知错误'))
  if (/network error|timeout|econnaborted|failed to fetch|abort/i.test(m)) {
    return '连不上服务器。请检查：① 手机和电脑连的是同一个 WiFi；'
      + '② 用百度/QQ/UC 等浏览器时，请在「设置→高级设置」关闭「云加速/极速模式」后刷新重试，'
      + '或改用系统自带浏览器打开本页'
  }
  return m
}

/** 生成 20261107-142530 形式的时间戳，用于默认文档名 */
export function stamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}
