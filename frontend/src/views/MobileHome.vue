<template>
  <div class="m-page">
    <h2 class="m-title">📄 手机端</h2>

    <div class="m-hero">
      <div class="m-hero-t">试卷智能扫描识别系统</div>
      <div class="m-hero-s">手机拍照录入，电脑端继续处理</div>
    </div>

    <div class="m-grid">
      <router-link to="/m/grade" class="m-entry">
        <span class="m-entry-icon">📷</span>
        <span class="m-entry-t">拍照阅卷</span>
        <span class="m-entry-s">拍答题卡，自动判分出结果</span>
      </router-link>
      <router-link to="/m/scan" class="m-entry">
        <span class="m-entry-icon">🧾</span>
        <span class="m-entry-t">拍照传卷</span>
        <span class="m-entry-s">拍试卷上传，电脑端识别切题</span>
      </router-link>
    </div>

    <div class="m-card">
      <div class="m-card-head">电脑版功能入口</div>
      <router-link v-for="m in links" :key="m.path" :to="m.path" class="m-link">
        <span>{{ m.label }}</span><span class="m-arrow">›</span>
      </router-link>
      <div class="m-pc" @click="goPc">在手机上使用完整电脑版界面 →</div>
    </div>
  </div>
</template>

<script setup>
const links = [
  { path: '/grading', label: '扫描阅卷' },
  { path: '/sheets', label: '答题卡管理' },
  { path: '/papers', label: '智能组卷' },
  { path: '/questions', label: '题库搜题' },
  { path: '/scan', label: '扫描识别' },
]

function goPc() {
  // 记住选择后不再自动跳手机版
  sessionStorage.setItem('pcPref', '1')
  window.location.href = '/'
}
</script>

<style scoped>
.m-page { max-width: 560px; margin: 0 auto; padding: 14px 12px 40px; }
.m-title { text-align: center; font-size: 20px; margin: 6px 0 12px; }
.m-hero {
  background: linear-gradient(135deg, #409eff, #6f42c1); color: #fff;
  border-radius: 14px; padding: 22px 16px; text-align: center; margin-bottom: 14px;
}
.m-hero-t { font-size: 17px; font-weight: 700; }
.m-hero-s { font-size: 12px; opacity: .85; margin-top: 6px; }
.m-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 14px; }
.m-entry {
  background: #fff; border-radius: 12px; padding: 18px 14px; text-align: center;
  box-shadow: 0 1px 4px rgba(0,0,0,.06); text-decoration: none; color: #303133;
  display: flex; flex-direction: column; gap: 4px; align-items: center;
}
.m-entry-icon { font-size: 30px; }
.m-entry-t { font-size: 15px; font-weight: 600; }
.m-entry-s { font-size: 11px; color: #909399; }
.m-card { background: #fff; border-radius: 12px; padding: 14px; box-shadow: 0 1px 4px rgba(0,0,0,.06); }
.m-card-head { font-weight: 600; font-size: 14px; margin-bottom: 6px; }
.m-link {
  display: flex; justify-content: space-between; align-items: center;
  padding: 11px 2px; border-bottom: 1px dashed #f0f0f0; text-decoration: none;
  color: #303133; font-size: 14px;
}
.m-arrow { color: #c0c4cc; }
.m-pc { text-align: center; color: #409eff; font-size: 12px; padding-top: 12px; cursor: pointer; }
</style>
