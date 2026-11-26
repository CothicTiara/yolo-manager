import { createRouter, createWebHistory } from 'vue-router'
import { watch } from 'vue'
import { t, locale } from '@/i18n'

const routes = [
  {
    path: '/',
    component: () => import('@/layout/Layout.vue'),
    redirect: '/datasets',
    children: [
      {
        path: 'datasets',
        name: 'Datasets',
        component: () => import('@/views/DatasetList.vue'),
        meta: { title: '数据集管理' }
      },
      {
        path: 'datasets/:id/annotate',
        name: 'Annotate',
        component: () => import('@/views/AnnotateView.vue'),
        meta: { title: '在线标注' }
      },
      {
        path: 'train',
        name: 'Train',
        component: () => import('@/views/TrainList.vue'),
        meta: { title: '训练任务' }
      },
      {
        path: 'models',
        name: 'Models',
        component: () => import('@/views/ModelList.vue'),
        meta: { title: '模型版本' }
      },
      {
        path: 'models/:id/test',
        name: 'ModelTest',
        component: () => import('@/views/ModelTest.vue'),
        meta: { title: '调用测试' }
      },
      {
        path: 'base-models',
        name: 'BaseModels',
        component: () => import('@/views/BaseModelList.vue'),
        meta: { title: '基础模型' }
      },
      {
        path: 'guide',
        name: 'Guide',
        component: () => import('@/views/GuideView.vue'),
        meta: { title: '使用指南' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// meta.title 存的是中文原文，正好当作 i18n 的 key；英文环境查 en.js 翻译
const applyTitle = (key) => {
  document.title = (key ? t(key) + ' - ' : '') + t('YOLO 模型管理平台')
}

router.afterEach((to) => applyTitle(to.meta?.title))

watch(locale, () => applyTitle(router.currentRoute.value.meta?.title))

export default router