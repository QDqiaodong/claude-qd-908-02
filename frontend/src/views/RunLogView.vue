<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const turbines = ref([])
const farms = ref([])
const turbineId = ref(null)
const logs = ref([])
const cursor = ref(12)

const form = ref(emptyForm())

function emptyForm () {
  return {
    recordTime: '',
    windSpeedMs: 8,
    activePowerKw: 3000,
    dailyEnergyKwh: 48000,
    windDirection: '西北',
    temperatureC: 18,
    operator: '',
    note: ''
  }
}

function pad (n) { return String(n).padStart(2, '0') }
function nowIso (offset) {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00${offset || '+08:00'}`
}

const currentTurbine = computed(() => turbines.value.find((t) => t.id === turbineId.value) || null)
const offsetText = computed(() => {
  const farm = farms.value.find((f) => f.id === (currentTurbine.value ? currentTurbine.value.farmId : props.farmId))
  return farm ? farm.offsetText : '+08:00'
})
const rated = computed(() => Number(currentTurbine.value?.ratedPowerKw || 5000))
const limit = computed(() => rated.value * 24)

/** 24 个小时槽位：每个槽位取该小时最新的一条读数 */
const slots = computed(() => {
  const arr = Array.from({ length: 24 }, (_, h) => ({ hour: h, log: null }))
  for (const l of logs.value) {
    if (!l.recordLocal) continue
    const hh = parseInt(l.recordLocal.slice(11, 13), 10)
    if (Number.isNaN(hh)) continue
    if (!arr[hh].log || l.recordLocal > arr[hh].log.recordLocal) arr[hh].log = l
  }
  return arr
})

const selected = computed(() => slots.value[cursor.value]?.log || null)

const linePoints = computed(() => {
  const pts = []
  slots.value.forEach((s) => {
    if (!s.log) return
    const x = (s.hour + 0.5) * 100
    const y = (1 - Math.min(1, Number(s.log.windSpeedMs || 0) / 25)) * 1000
    pts.push(`${x},${y.toFixed(0)}`)
  })
  return pts.join(' ')
})

const filled = computed(() => slots.value.filter((s) => s.log).length)

function hourLabel (h) { return pad(h) + ':00' }

function pick (h) {
  cursor.value = h
  const log = slots.value[h].log
  if (log) {
    const date = log.recordLocal.slice(0, 10)
    form.value = {
      recordTime: `${date}T${pad(h)}:00:00${offsetText.value}`,
      windSpeedMs: Number(log.windSpeedMs),
      activePowerKw: Number(log.activePowerKw),
      dailyEnergyKwh: Number(log.dailyEnergyKwh),
      windDirection: log.windDirection || '西北',
      temperatureC: Number(log.temperatureC || 18),
      operator: log.operator || '',
      note: log.note || ''
    }
  } else {
    const d = new Date()
    form.value = {
      ...emptyForm(),
      recordTime: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(h)}:00:00${offsetText.value}`
    }
  }
}

async function loadTurbines () {
  turbines.value = await api.turbine.grid(props.farmId)
  if (!farms.value.length) farms.value = await api.farm.vo()
  if (!turbineId.value && turbines.value.length) {
    const fromQuery = Number(router.currentRoute.value?.query?.turbineId)
    turbineId.value = turbines.value.some((t) => t.id === fromQuery) ? fromQuery : turbines.value[0].id
  }
}

async function loadLogs () {
  if (!turbineId.value) { logs.value = []; return }
  logs.value = await api.runlog.vo(turbineId.value)
  if (!form.value.recordTime) form.value.recordTime = nowIso(offsetText.value)
}

async function submit () {
  try {
    if (selected.value && form.value.recordTime === selected.value.recordTime) {
      await api.runlog.update(selected.value.id, { ...form.value, turbineId: turbineId.value })
      ElMessage.success('该时刻读数已更新')
    } else {
      await api.runlog.create({ ...form.value, turbineId: turbineId.value })
      ElMessage.success('读数已登记')
    }
    await loadLogs()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeLog (row) {
  await ElMessageBox.confirm(`确认删除 ${row.recordLocal} 的读数？`, '删除读数', { type: 'warning' })
  try {
    await api.runlog.remove(row.id)
    ElMessage.success('读数已删除')
    await loadLogs()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await loadTurbines()
  await loadLogs()
})
watch(() => props.farmId, async () => {
  turbineId.value = null
  await loadTurbines()
  await loadLogs()
})
watch(turbineId, () => {
  form.value.recordTime = ''
  loadLogs()
})
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">运行日志</span>
      <span class="view-tip">24 小时功率柱 + 风速折线混合图，拖动时刻游标或点柱填该时刻读数</span>
      <el-button size="small" style="margin-left: auto" @click="router.push('/turbine')">← 机位总览</el-button>
    </div>

    <div class="chip-bar">
      <el-select v-model="turbineId" size="small" style="width: 260px">
        <el-option v-for="t in turbines" :key="t.id" :value="t.id"
          :label="`${t.unitCode} ${t.unitName}（${t.model}）`" />
      </el-select>
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">额定 {{ rated.toLocaleString() }} kW</span>
      <span class="chip">日电量上限 {{ limit.toLocaleString() }} kWh</span>
      <span class="chip">已填槽位 {{ filled }}/24</span>
      <span class="chip" :class="{ bad: currentTurbine && (currentTurbine.status === 'FAULT' || currentTurbine.isolationLocked) }">
        {{ currentTurbine ? currentTurbine.statusName : '—' }}
      </span>
      <span v-if="currentTurbine && currentTurbine.isolationLocked" class="chip bad">
        🔒 隔离票 {{ currentTurbine.activeTicketNo }} {{ currentTurbine.activeTicketStatusName }} · 禁登记
      </span>
      <span class="chip tz-tag">{{ offsetText }}</span>
    </div>

    <!-- ---------- 24 小时混合图 ---------- -->
    <div class="panel">
      <div class="chart-wrap">
        <div class="chart-bars">
          <div
            v-for="s in slots"
            :key="s.hour"
            class="chart-bar"
            :class="{ empty: !s.log, sel: cursor === s.hour }"
            :style="{ height: s.log ? Math.max(3, (Number(s.log.activePowerKw) / rated) * 100) + '%' : '3%' }"
            :title="s.log ? `${hourLabel(s.hour)} 功率 ${s.log.activePowerKw} kW` : hourLabel(s.hour)"
            @click="pick(s.hour)"
          ></div>
        </div>

        <svg class="chart-line" viewBox="0 0 2400 1000" preserveAspectRatio="none">
          <polyline
            v-if="linePoints"
            :points="linePoints"
            fill="none"
            stroke="#f08c00"
            stroke-width="3"
            vector-effect="non-scaling-stroke"
          />
        </svg>

        <div class="chart-cursor" :style="{ left: `calc(12px + (100% - 24px) * ${(cursor + 0.5) / 24})` }">
          <b>{{ hourLabel(cursor) }}</b>
        </div>

        <div class="chart-axis">
          <span v-for="h in [0, 4, 8, 12, 16, 20]" :key="h">{{ hourLabel(h) }}</span>
          <span>24:00</span>
        </div>
      </div>

      <div class="chart-legend">
        <span><i style="background: linear-gradient(180deg,#4db6ac,#00838f)"></i>有功功率（柱，满高 = 额定功率）</span>
        <span><i style="background: #f08c00"></i>风速（折线，满高 = 25 m/s）</span>
        <span><i style="background: #e2483d"></i>时刻游标</span>
      </div>

      <el-slider v-model="cursor" :min="0" :max="23" show-stops style="margin-top: 10px" />
    </div>

    <!-- ---------- 读数表单（点柱回填） ---------- -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">{{ hourLabel(cursor) }} 读数</span>
        <span class="panel-sub">
          {{ selected ? '已存在该时刻读数，提交即更新' : '该时刻暂无读数，提交即新登记' }}
        </span>
      </div>
      <el-alert
        v-if="currentTurbine && currentTurbine.isolationLocked"
        type="error" show-icon :closable="false" style="margin-bottom: 8px"
        :title="`机位隔离锁定中（${currentTurbine.activeTicketNo} · ${currentTurbine.activeTicketStatusName}），安全措施未恢复，禁止登记发电读数`"
        description="请先在检修模块完成隔离票复电恢复，再恢复并网登记读数。"
      />
      <el-form :inline="true" size="small" :disabled="!!(currentTurbine && currentTurbine.isolationLocked)">
        <el-form-item label="记录时刻"><el-input v-model="form.recordTime" style="width: 230px" /></el-form-item>
        <el-form-item label="风速 m/s"><el-input-number v-model="form.windSpeedMs" :min="0" :max="40" :step="0.1" /></el-form-item>
        <el-form-item label="有功 kW"><el-input-number v-model="form.activePowerKw" :min="0" :max="rated" :step="10" /></el-form-item>
        <el-form-item label="日发电 kWh"><el-input-number v-model="form.dailyEnergyKwh" :min="1" :max="limit" :step="100" /></el-form-item>
        <el-form-item label="风向"><el-input v-model="form.windDirection" style="width: 90px" /></el-form-item>
        <el-form-item label="机舱温度"><el-input-number v-model="form.temperatureC" :step="0.5" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="form.operator" style="width: 110px" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" style="width: 160px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="submit">{{ selected ? '更新该时刻' : '登记读数' }}</el-button></el-form-item>
      </el-form>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">读数明细</span><span class="panel-sub">recordTz 是这条读数所属风场的时区（ZoneId），记录时刻按它换算</span></div>
      <el-table :data="logs" size="small" max-height="300">
        <el-table-column prop="recordLocal" label="记录时刻" width="150" />
        <el-table-column prop="recordTz" label="时区列" width="80" />
        <el-table-column prop="reportLocal" label="上报时刻" width="150" />
        <el-table-column prop="windSpeedMs" label="风速" width="80" />
        <el-table-column prop="activePowerKw" label="有功 kW" width="100" />
        <el-table-column prop="dailyEnergyKwh" label="日发电 kWh" width="120" />
        <el-table-column label="负荷率" width="100">
          <template #default="{ row }">{{ Math.round((row.loadRate || 0) * 100) }}%</template>
        </el-table-column>
        <el-table-column prop="windDirection" label="风向" width="80" />
        <el-table-column prop="operator" label="值班员" width="110" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeLog(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
