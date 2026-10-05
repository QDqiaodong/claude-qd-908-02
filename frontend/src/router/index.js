import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import TurbineView from '../views/TurbineView.vue'
import RunLogView from '../views/RunLogView.vue'
import GridView from '../views/GridView.vue'
import WorkOrderView from '../views/WorkOrderView.vue'
import SparePartView from '../views/SparePartView.vue'
import StatsView from '../views/StatsView.vue'

const routes = [
  { path: '/', redirect: '/turbine' },
  { path: '/turbine', name: 'turbine', component: TurbineView },
  { path: '/run', name: 'run', component: RunLogView },
  { path: '/grid', name: 'grid', component: GridView },
  { path: '/maint', name: 'maint', component: WorkOrderView },
  { path: '/part', name: 'part', component: SparePartView },
  { path: '/stats', name: 'stats', component: StatsView },
  { path: '/:pathMatch(.*)*', redirect: '/turbine' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
