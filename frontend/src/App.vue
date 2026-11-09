<template>
  <!-- 手机端专属页面（meta.plain）不套后台布局，直接全屏渲染 -->
  <router-view v-if="$route.meta.plain" />

  <el-container v-else class="app-shell" :class="{ 'is-mobile': mobile }">
    <!-- 桌面端：左侧栏（可收缩为纯图标） -->
    <el-aside v-if="!mobile" :width="collapsed ? '64px' : '220px'" class="app-aside">
      <div class="logo" :class="{ mini: collapsed }">
        <span class="logo-icon">📄</span>
        <span v-if="!collapsed">试卷智能识别系统</span>
      </div>
      <el-menu :default-active="$route.path" router :collapse="collapsed" :collapse-transition="false"
               background-color="#1f2d3d" text-color="#bfcbd9" active-text-color="#409EFF">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.label }}</template>
        </el-menu-item>
      </el-menu>
      <div class="aside-fold" @click="toggleCollapse">
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起菜单</span>
      </div>
    </el-aside>

    <!-- 手机端：顶栏 + 抽屉菜单（可打开/收起） -->
    <template v-else>
      <el-header class="m-topbar" height="46px">
        <el-icon class="m-burger" @click="menuOpen = true"><Menu /></el-icon>
        <span class="m-topbar-title">试卷识别系统</span>
        <router-link to="/m" class="m-home-link">手机版</router-link>
      </el-header>
      <el-drawer v-model="menuOpen" direction="ltr" size="230px" :with-header="false">
        <div class="logo drawer-logo">
          <span class="logo-icon">📄</span>
          <span>试卷智能识别系统</span>
          <el-icon class="drawer-close" @click="menuOpen = false"><Close /></el-icon>
        </div>
        <el-menu :default-active="$route.path" router background-color="#1f2d3d" text-color="#bfcbd9"
                 active-text-color="#409EFF" @select="menuOpen = false">
          <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>{{ m.label }}
          </el-menu-item>
        </el-menu>
      </el-drawer>
    </template>

    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { Menu, Close, Fold, Expand, Odometer, Camera, Collection, Document, Grid, Checked, UploadFilled, DataAnalysis } from '@element-plus/icons-vue'
import { isMobile } from './utils/mobile'

const menus = [
  { path: '/', icon: Odometer, label: '工作台' },
  { path: '/scan', icon: Camera, label: '扫描识别' },
  { path: '/questions', icon: Collection, label: '题库搜题' },
  { path: '/import', icon: UploadFilled, label: '文档导入' },
  { path: '/papers', icon: Document, label: '智能组卷' },
  { path: '/sheets', icon: Grid, label: '答题卡' },
  { path: '/grading', icon: Checked, label: '扫描阅卷' },
  { path: '/grading-stats', icon: DataAnalysis, label: '阅卷统计' },
]

const route = useRoute()
const mobile = ref(isMobile())
const menuOpen = ref(false)
// 菜单收缩状态：记忆用户选择；窄屏（含平板）默认收起
const collapsed = ref(localStorage.getItem('menuCollapsed')
  ? localStorage.getItem('menuCollapsed') === '1'
  : window.innerWidth <= 1024)

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('menuCollapsed', collapsed.value ? '1' : '0')
}

const onResize = () => { mobile.value = isMobile() }

watch(() => route.path, () => { menuOpen.value = false })

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>

<style>
.app-shell { height: 100vh; }
.app-aside { background: #1f2d3d; }
.logo {
  height: 60px; display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 600; font-size: 15px; gap: 8px;
}
.drawer-logo { height: 56px; }
.app-main { background: #f5f7fa; padding: 20px; overflow: auto; }
.app-aside { display: flex; flex-direction: column; transition: width .2s; }
.app-aside .el-menu { border-right: none; flex: 1; overflow: hidden; }
.aside-fold {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  height: 44px; color: #bfcbd9; cursor: pointer; font-size: 13px; border-top: 1px solid rgba(255,255,255,.08);
  flex-shrink: 0;
}
.aside-fold:hover { color: #409EFF; }
.logo.mini { font-size: 0; }
.drawer-close { margin-left: auto; cursor: pointer; color: #909399; padding-right: 4px; }

/* 手机端布局 */
.app-shell.is-mobile { flex-direction: column; }  /* el-container 误判为横向，顶栏被挤到左侧 */
.m-topbar {
  display: flex; align-items: center; gap: 10px; background: #1f2d3d; color: #fff;
  padding: 0 10px; min-width: 0; box-sizing: border-box; width: 100%;
}
.m-burger { font-size: 22px; cursor: pointer; flex-shrink: 0; }
.m-topbar-title { flex: 1; font-size: 14px; font-weight: 600; min-width: 0; overflow: hidden; white-space: nowrap; }
.m-home-link { color: #79bbff; font-size: 12px; text-decoration: none; flex-shrink: 0; }

/* 手机端全局适配：对话框、弹层、间距、表格横向滚动 */
@media (max-width: 768px) {
  .app-main { padding: 10px; overflow-x: hidden; }
  .el-dialog {
    --el-dialog-width: 94vw !important;
    max-width: 94vw !important;
    margin-top: 4vh !important;
  }
  .el-message-box { width: 92vw !important; }
  .el-form--inline .el-form-item { margin-right: 8px; }
  /* 表格在手机上可横向滑动，且滚动条常显、加粗，避免「右侧显示不全」 */
  .el-scrollbar__bar { opacity: 1 !important; }
  .el-scrollbar__bar.is-horizontal { height: 8px; }
  .el-descriptions { font-size: 12px; }
  .el-table { font-size: 12px; }
  /* 卡片更紧凑，内容可视区更大 */
  .page-card { padding: 12px 10px; border-radius: 8px; }
  .page-card h3 { font-size: 15px; margin-bottom: 8px; }
  /* 触控目标加大，操作更方便 */
  .el-button { min-height: 34px; }
  .el-button--small { height: 30px; padding: 0 10px; }
  .el-input__inner, .el-input__wrapper { min-height: 32px; }
  .el-table .cell { padding: 0 6px; }
  .el-table td.el-table__cell > .cell,
  .el-table th.el-table__cell > .cell { line-height: 20px; }
  .el-table td.el-table__cell, .el-table th.el-table__cell { padding: 6px 0; }
  /* 低优先级列在窄屏隐藏，减少横向滚动 */
  .el-table .col-optional { display: none !important; }
  .el-tabs__item { padding: 0 10px; }
  .el-pagination { --el-pagination-button-width: 28px; }
}
</style>
