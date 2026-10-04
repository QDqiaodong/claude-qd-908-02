<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'
import router from '../router'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const overview = ref(null)
const daily = ref([])
const monthly = ref([])
const farmRows = ref([])
const turbineRows = ref([])
const orderStat = ref([])
const partStat = ref([])

const maxDaily = computed(() => Math.max(1, ...daily.value.map((d) => Number(d.energyKwh || 0))))
const maxMonthly = computed(() => Math.max(1, ...monthly.value.map((d) => Number(d.energyKwh || 0))))
const totalDaily = computed(() => daily.value.reduce((s, d) => s + Number(d.energyKwh || 0), 0))
const totalEquivalent = computed(() =>
  farmRows.value.reduce((s, f) => s + Number(f.equivalentHours || 0), 0))

async function load () {
  const [o, d, m, f, t, os, ps] = await Promise.all([
    api.stats.overview(),
    api.stats.daily(props.farmId),
    api.stats.monthly(props.farmId),
    api.stats.byFarm(),
    api.stats.byTurbine(props.farmId),
    api.stats.workOrderStatus(),
    api.stats.partCategory()
  ])
  overview.value = o
  daily.value = d
  monthly.value = m
  farmRows.value = f
  turbineRows.value = t
  orderStat.value = os
  partStat.value = ps
}

onMounted(load)
watch(() => props.farmId, load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">发电统计</span>
      <span class="view-tip">日 / 月发电量、等效利用小时，按风场与机位两种口径聚合（数据全部来自运行日志）</span>
      <el-button size="small" style="margin-left: auto" @click="router.push('/turbine')">← 机位总览</el-button>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">统计天数 {{ daily.length }}</span>
      <span class="chip">区间发电 {{ Math.round(totalDaily).toLocaleString() }} kWh</span>
      <span class="chip">等效利用小时合计 {{ Number(totalEquivalent).toFixed(1) }} h</span>
    </div>

    <!-- ---------- 概览 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">全场概览</span></div>
      <div class="stat-grid" v-if="overview">
        <div class="stat-box"><b>{{ overview.farmCount }}</b><span>风场</span></div>
        <div class="stat-box"><b>{{ overview.turbineCount }}</b><span>机位</span></div>
        <div class="stat-box"><b>{{ overview.onlineCount }}</b><span>并网</span></div>
        <div class="stat-box"><b>{{ overview.maintainCount }}</b><span>检修</span></div>
        <div class="stat-box"><b>{{ overview.faultCount }}</b><span>故障</span></div>
        <div class="stat-box"><b>{{ overview.runLogCount }}</b><span>运行日志</span></div>
        <div class="stat-box"><b>{{ overview.workOrderCount }}</b><span>检修工单</span></div>
        <div class="stat-box"><b>{{ overview.workingOrderCount }}</b><span>作业中</span></div>
        <div class="stat-box"><b>{{ Math.round(overview.todayEnergyKwh).toLocaleString() }}</b><span>今日 kWh</span></div>
        <div class="stat-box"><b>{{ Math.round(overview.totalEnergyKwh).toLocaleString() }}</b><span>累计 kWh</span></div>
      </div>
    </div>

    <!-- ---------- 日 / 月发电量 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">日发电量</span><span class="panel-sub">按场站本地时区归日</span></div>
      <div class="mini-bars" v-if="daily.length">
        <div v-for="d in daily" :key="d.period" class="mini-bar">
          <i :style="{ height: Math.max(3, (Number(d.energyKwh) / maxDaily) * 100) + '%' }"
             :title="`${d.label} ${Number(d.energyKwh).toLocaleString()} kWh · ${d.equivalentHours} h`"></i>
          <span>{{ d.period.slice(5) }}</span>
        </div>
      </div>
      <div class="empty" v-else>暂无日发电量数据</div>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">月发电量</span></div>
      <div class="mini-bars" v-if="monthly.length">
        <div v-for="d in monthly" :key="d.period" class="mini-bar">
          <i :style="{ height: Math.max(3, (Number(d.energyKwh) / maxMonthly) * 100) + '%' }"
             :title="`${d.label} ${Number(d.energyKwh).toLocaleString()} kWh`"></i>
          <span>{{ d.period }}</span>
        </div>
      </div>
      <div class="empty" v-else>暂无月发电量数据</div>
    </div>

    <!-- ---------- 按风场 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">按风场聚合</span><span class="panel-sub">时区列来自 wind_farm.time_zone</span></div>
      <el-table :data="farmRows" size="small">
        <el-table-column prop="farmCode" label="风场编号" width="110" />
        <el-table-column prop="farmName" label="风场" min-width="150" />
        <el-table-column label="场站时区" width="170">
          <template #default="{ row }">
            <span class="tz-tag">{{ row.timeZoneName }} {{ row.offsetText }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="turbineCount" label="机位" width="70" />
        <el-table-column prop="onlineCount" label="并网" width="70" />
        <el-table-column label="额定容量 kW" width="120">
          <template #default="{ row }">{{ Number(row.ratedCapacityKw).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="日均 kWh" width="120">
          <template #default="{ row }">{{ Number(row.dailyEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="月发电 kWh" width="130">
          <template #default="{ row }">{{ Number(row.monthlyEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="累计 kWh" width="130">
          <template #default="{ row }">{{ Number(row.totalEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="equivalentHours" label="等效小时 h" width="110" />
        <el-table-column prop="logCount" label="读数" width="80" />
      </el-table>
    </div>

    <!-- ---------- 按机位 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">按机位聚合</span></div>
      <el-table :data="turbineRows" size="small" max-height="330">
        <el-table-column prop="unitCode" label="机位" width="80" />
        <el-table-column prop="farmName" label="风场" min-width="140" />
        <el-table-column prop="model" label="机型" min-width="150" />
        <el-table-column prop="statusName" label="状态" width="80" />
        <el-table-column label="额定 kW" width="100">
          <template #default="{ row }">{{ Number(row.ratedPowerKw).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="最近一日 kWh" width="130">
          <template #default="{ row }">{{ Number(row.dailyEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="本月 kWh" width="120">
          <template #default="{ row }">{{ Number(row.monthlyEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="累计 kWh" width="130">
          <template #default="{ row }">{{ Number(row.totalEnergyKwh).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="平均功率 kW" width="120">
          <template #default="{ row }">{{ Number(row.avgPowerKw).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="equivalentHours" label="等效小时 h" width="110" />
        <el-table-column prop="logCount" label="读数" width="70" />
      </el-table>
    </div>

    <!-- ---------- 分布 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">检修工单状态分布</span></div>
      <div class="chip-bar" style="margin-bottom: 0">
        <span v-for="s in orderStat" :key="s.status" class="chip" :class="{ on: s.status === 'WORKING' }">
          {{ s.statusName }} {{ s.total }}
        </span>
        <span v-if="!orderStat.length" class="chip">暂无数据</span>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">备件分类库存分布</span></div>
      <div class="chip-bar" style="margin-bottom: 0">
        <span v-for="c in partStat" :key="c.category" class="chip" :class="{ warn: c.lowStockCount > 0 }">
          {{ c.categoryName }}：{{ c.partCount }} 种 / {{ c.totalStock }} 件 / 低库存 {{ c.lowStockCount }}
        </span>
        <span v-if="!partStat.length" class="chip">暂无数据</span>
      </div>
    </div>
  </div>
</template>
