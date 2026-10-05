<script setup>
import { computed, onMounted, ref } from 'vue'
import router from './router'
import api from './api'

import TurbineView from './views/TurbineView.vue'
import RunLogView from './views/RunLogView.vue'
import GridView from './views/GridView.vue'
import WorkOrderView from './views/WorkOrderView.vue'
import SparePartView from './views/SparePartView.vue'
import StatsView from './views/StatsView.vue'

// 这个仓没有左侧栏、没有顶部菜单栏：
// 顶部只有一条「风场切换器」，主区是该风场下所有机位的大卡片网格，卡片本身就是导航。
// main.js 里没有 app.use(router)，这里自己驱动路由实例并用 <component :is> 渲染。
const viewMap = {
  '/turbine': TurbineView,
  '/run': RunLogView,
  '/grid': GridView,
  '/maint': WorkOrderView,
  '/part': SparePartView,
  '/stats': StatsView
}

const currentPath = computed(() => router.currentRoute.value?.path || '/turbine')
const currentView = computed(() => viewMap[currentPath.value] || TurbineView)

const farms = ref([])
const farmId = ref(null)
const overview = ref(null)

const currentFarm = computed(() => farms.value.find((f) => f.id === farmId.value) || null)

async function loadFarms () {
  try {
    farms.value = await api.farm.vo()
    if (farmId.value === null && farms.value.length) {
      farmId.value = farms.value[0].id
    }
  } catch (e) {
    farms.value = []
  }
}

async function loadOverview () {
  try {
    overview.value = await api.stats.overview()
  } catch (e) {
    overview.value = null
  }
}

function pickFarm (id) {
  farmId.value = id
}

function home () {
  router.push('/turbine')
}

onMounted(async () => {
  await loadFarms()
  loadOverview()
  // 路由没有 install，初始 currentRoute 是 START_LOCATION，这里按地址栏补一次导航
  const hash = window.location.hash.replace(/^#/, '')
  router.replace(hash.startsWith('/') && hash !== '/' ? hash : '/turbine')
})
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <div class="brand-mark" @click="home" style="cursor: pointer"><span>风</span></div>
      <div>
        <div class="brand-title">陆上风电场 · 风机与检修发电管理</div>
        <div class="brand-sub">风场 → 机位 → 运行日志 → 电网限功率 → 检修工单 → 备件更换 → 发电统计</div>
      </div>
      <el-button size="small" type="danger" plain @click="router.push('/grid')">电网限功率</el-button>
      <el-button size="small" type="primary" plain @click="router.push('/part')">备件与更换</el-button>

      <!-- 顶部唯一的一条导航：风场切换器 -->
      <div class="farm-switch">
        <button
          v-for="farm in farms"
          :key="farm.id"
          class="farm-btn"
          :class="{ active: farm.id === farmId }"
          @click="pickFarm(farm.id)"
        >
          <span class="fb-code">{{ farm.farmCode }}</span>
          <span class="fb-name">{{ farm.farmName }}</span>
          <span class="fb-tz">{{ farm.timeZoneName }} {{ farm.offsetText }}</span>
        </button>
      </div>

      <div class="top-stats" v-if="overview">
        <div class="top-stat"><b>{{ overview.farmCount }}</b><span>风场</span></div>
        <div class="top-stat"><b>{{ overview.turbineCount }}</b><span>机位</span></div>
        <div class="top-stat"><b>{{ overview.onlineCount }}</b><span>并网</span></div>
        <div class="top-stat"><b>{{ overview.faultCount }}</b><span>故障</span></div>
        <div class="top-stat"><b>{{ overview.runLogCount }}</b><span>运行日志</span></div>
        <div class="top-stat"><b>{{ overview.workOrderCount }}</b><span>检修工单</span></div>
        <div class="top-stat"><b>{{ overview.activeTicketCount || 0 }}</b><span>隔离中</span></div>
      </div>
    </header>

    <main class="app-content">
      <component
        :is="currentView"
        :farm-id="farmId"
        :farm-name="currentFarm ? currentFarm.farmName : ''"
        :key="currentPath + '#' + (farmId || 'all')"
      />
    </main>
  </div>
</template>
