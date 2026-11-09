import { createRouter, createWebHistory } from 'vue-router'
import { isMobile } from '../utils/mobile'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('../views/Dashboard.vue') },
    { path: '/scan', component: () => import('../views/ScanView.vue') },
    { path: '/questions', component: () => import('../views/QuestionBank.vue') },
    { path: '/import', component: () => import('../views/DocumentImport.vue') },
    { path: '/papers', component: () => import('../views/PaperBuilder.vue') },
    { path: '/papers/:id', component: () => import('../views/PaperDetail.vue') },
    { path: '/sheets', component: () => import('../views/SheetList.vue') },
    { path: '/grading', component: () => import('../views/GradingView.vue') },
    { path: '/grading-stats', component: () => import('../views/GradingStats.vue') },
    // 手机端专属页面：无侧栏独立布局
    { path: '/m', component: () => import('../views/MobileHome.vue'), meta: { plain: true } },
    { path: '/m/grade', component: () => import('../views/MobileGrade.vue'), meta: { plain: true } },
    { path: '/m/scan', component: () => import('../views/MobileScan.vue'), meta: { plain: true } },
  ],
})

// 手机访问站点首页时自动进手机版主页；手机版里点「完整电脑版界面」（写入 pcPref）后不再跳转
router.beforeEach((to, from, next) => {
  if (to.path === '/' && isMobile() && !sessionStorage.getItem('pcPref')) {
    next('/m')
  } else {
    next()
  }
})

export default router
