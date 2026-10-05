<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const farms = ref([])
const turbines = ref([])
const orders = ref([])
const deviations = ref([])
const selectedId = ref(null)
const loading = ref(false)
const dialogVisible = ref(false)
const detailTab = ref('order')

const form = ref(emptyForm())
const manualLimits = ref({})
const statusAction = ref({ operator: '', note: '', releaseResult: '' })

function emptyForm () {
  return {
    orderNo: '',
    targetLimitKw: 8000,
    targetScope: 'FULL',
    allocationMode: 'PROPORTIONAL',
    priority: 100,
    overlapMode: 'COEXIST',
    startTime: '',
    endTime: '',
    releaseCondition: '',
    dutyOperator: '',
    note: '',
    turbineIds: []
  }
}

function pad (n) { return String(n).padStart(2, '0') }
function toIso (value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const off = offsetText.value
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00${off}`
}
function localInput (addHours = 1) {
  const d = new Date(Date.now() + addHours * 3600000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

const currentFarm = computed(() => farms.value.find((f) => f.id === props.farmId) || null)
const offsetText = computed(() => currentFarm.value?.offsetText || '+08:00')
const selected = computed(() => orders.value.find((o) => o.id === selectedId.value) || null)
const planned = computed(() => orders.value.filter((o) => o.status === 'PLANNED'))
const active = computed(() => orders.value.filter((o) => o.status === 'EXECUTING' || o.status === 'PAUSED'))
const closed = computed(() => orders.value.filter((o) => o.status === 'ENDED' || o.status === 'CANCELLED'))
const selectedDeviations = computed(() => deviations.value.filter((d) => d.governingOrderId === selectedId.value))
const openDeviations = computed(() => deviations.value.filter((d) => d.latestFlag !== false && !['RELEASED', 'SUPERSEDED'].includes(d.status)))
const runnableTurbines = computed(() => turbines.value.filter((t) => t.status === 'ONLINE'))
const unavailableTurbines = computed(() => turbines.value.filter((t) => t.status !== 'ONLINE'))
const allocationRows = computed(() => {
  if (!selected.value) return []
  if (selected.value.allocations.length) return selected.value.allocations
  return turbines.value
    .filter((t) => selected.value.turbineIds.includes(t.id))
    .map((t) => ({
      turbineId: t.id,
      unitCode: t.unitCode,
      unitName: t.unitName,
      ratedPowerKw: t.ratedPowerKw,
      limitKw: manualLimits.value[t.id] || 0,
      runnable: t.status === 'ONLINE',
      turbineStatus: t.status,
      turbineStatusName: t.statusName,
      allocationState: t.status === 'ONLINE' ? 'ALLOCATED' : 'EXCLUDED',
      allocationStateName: t.status === 'ONLINE' ? '待确认' : '不占额度',
      latestPowerKw: t.lastPowerKw,
      latestExceeded: false,
      note: t.status === 'ONLINE' ? null : `执行前状态为「${t.statusName}」，不占用场站目标额度`
    }))
})

async function load () {
  loading.value = true
  try {
    if (!props.farmId) return
    const [farmList, turbineList, orderList, devList] = await Promise.all([
      farms.value.length ? Promise.resolve(farms.value) : api.farm.vo(),
      api.turbine.grid(props.farmId),
      api.gridLimit.orders(props.farmId),
      api.gridLimit.deviations(props.farmId, null, false)
    ])
    farms.value = farmList
    turbines.value = turbineList
    orders.value = orderList
    deviations.value = devList
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate () {
  form.value = {
    ...emptyForm(),
    startTime: localInput(0),
    endTime: localInput(4),
    turbineIds: runnableTurbines.value.map((t) => t.id)
  }
  dialogVisible.value = true
}

async function submitCreate () {
  try {
    const payload = {
      ...form.value,
      startTime: toIso(form.value.startTime),
      endTime: toIso(form.value.endTime),
      turbineIds: form.value.targetScope === 'FULL' ? turbines.value.map((t) => t.id) : form.value.turbineIds
    }
    await api.gridLimit.create(payload)
    ElMessage.success('指令已登记，执行前必须确认机位分摊')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadSelected () {
  if (!selectedId.value) return
  try {
    const fresh = await api.gridLimit.order(selectedId.value)
    const idx = orders.value.findIndex((o) => o.id === fresh.id)
    if (idx >= 0) orders.value[idx] = fresh
    else orders.value.unshift(fresh)
    if (fresh.allocationMode === 'MANUAL') {
      const next = {}
      fresh.allocations.forEach((a) => {
        if (a.runnable) next[a.turbineId] = Number(manualLimits.value[a.turbineId] ?? a.limitKw ?? 0)
      })
      if (!Object.keys(next).length) {
        const selectedOnline = turbines.value.filter((t) => fresh.turbineIds.includes(t.id) && t.status === 'ONLINE')
        const selectedCapacity = selectedOnline.reduce((s, t) => s + Number(t.ratedPowerKw), 0)
        selectedOnline.forEach((t) => {
          next[t.id] = Number(manualLimits.value[t.id] ?? Math.round(Number(fresh.targetLimitKw) * Number(t.ratedPowerKw) / Math.max(1, selectedCapacity)))
        })
      }
      manualLimits.value = next
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function previewAllocation () {
  try {
    await loadSelected()
    const items = allocationRows.value
      .filter((a) => a.runnable)
      .map((a) => ({ turbineId: a.turbineId, limitKw: Number(manualLimits.value[a.turbineId] ?? a.limitKw ?? 0) }))
    const preview = await api.gridLimit.preview(selectedId.value, { items })
    if (preview.blockers?.length) {
      ElMessage.error(preview.blockers[0])
    } else {
      ElMessage.success(`预分摊合计 ${preview.allocatedTotalKw} kW，未超过目标 ${preview.targetLimitKw} kW`)
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function confirmAllocation () {
  try {
    const items = allocationRows.value
      .filter((a) => a.runnable)
      .map((a) => ({ turbineId: a.turbineId, limitKw: Number(manualLimits.value[a.turbineId] ?? a.limitKw ?? 0) }))
    await api.gridLimit.allocate(selectedId.value, {
      operator: selected.value.dutyOperator || '值班员',
      items,
      note: '执行前确认机位分摊'
    })
    ElMessage.success('分摊已后台落库')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doAction (name, success, needRelease = false) {
  try {
    const data = {
      operator: statusAction.value.operator || selected.value.dutyOperator || '值班员',
      note: statusAction.value.note || success,
      releaseResult: needRelease ? statusAction.value.releaseResult : undefined
    }
    if (needRelease && !data.releaseResult) {
      ElMessage.error('请先填写解除条件核对和解除结果')
      return
    }
    await api.gridLimit[name](selectedId.value, data)
    ElMessage.success(success)
    statusAction.value = { operator: '', note: '', releaseResult: '' }
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reallocate () {
  try {
    await api.gridLimit.reallocate(selectedId.value, { operator: '值班员', note: '机位状态变化，重新计算可运行额度' })
    ElMessage.success('已刷新，停运 / 检修 / 故障机位不再占额度')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function deviationAction (row, action, label) {
  try {
    const { value } = await ElMessageBox.prompt(`请记录${label}说明`, label, { inputType: 'textarea' })
    await api.gridLimit[action](row.id, { operator: '值班员', note: value })
    ElMessage.success(`${label}已留痕`)
    await refreshAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '操作失败')
  }
}

async function refreshAll () {
  await load()
  await loadSelected()
}

function selectOrder (row) {
  selectedId.value = row.id
  detailTab.value = 'order'
  loadSelected()
}

function tagType (status) {
  return { PLANNED: 'primary', EXECUTING: 'danger', PAUSED: 'warning', ENDED: 'success', CANCELLED: 'info' }[status] || 'info'
}
function scopeText (row) {
  if (row.targetScope === 'FULL') return '全场站'
  return `指定 ${row.turbineIds?.length || 0} 台`
}
function manualTotal (row) {
  return allocationRows.value.filter((a) => a.runnable).reduce((s, a) => s + Number(manualLimits.value[a.turbineId] ?? a.limitKw ?? 0), 0)
}

onMounted(load)
watch(() => props.farmId, () => {
  selectedId.value = null
  load()
})
</script>

<template>
  <div v-loading="loading">
    <div class="view-head">
      <span class="view-title">电网限功率指令</span>
      <span class="view-tip">登记、分摊、执行、重叠、偏差处置和解除轨迹全部后台落库；并行限值取最严格</span>
      <el-button size="small" type="danger" plain style="margin-left: auto" @click="openCreate">登记限功率指令</el-button>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '请选择风场' }}</span>
      <span class="chip">并网 {{ runnableTurbines.length }} 台</span>
      <span class="chip bad" v-if="unavailableTurbines.length">停运/检修/故障 {{ unavailableTurbines.length }} 台不占额度</span>
      <span class="chip">待执行 {{ planned.length }}</span>
      <span class="chip">执行/暂停 {{ active.length }}</span>
      <span class="chip bad" v-if="openDeviations.length">未闭环偏差 {{ openDeviations.length }}</span>
      <span class="chip tz-tag">{{ offsetText }}</span>
    </div>

    <el-row :gutter="12">
      <el-col :span="9">
        <div class="panel">
          <div class="panel-head"><span class="panel-title">待执行指令</span><span class="panel-sub">进入执行前必须完成分摊确认</span></div>
          <div v-for="o in planned" :key="o.id" class="limit-card" :class="{sel: selectedId === o.id}" @click="selectOrder(o)">
            <div><b>{{ o.orderNo }}</b><el-tag size="small" :type="tagType(o.status)" style="margin-left:8px">{{ o.statusName }}</el-tag></div>
            <div>{{ scopeText(o) }} · 目标 {{ o.targetLimitKw }} kW · {{ o.allocationModeName }}</div>
            <div>{{ o.startLocal }} ~ {{ o.endLocal }}</div>
            <div v-if="o.blockers?.length" class="danger-text">{{ o.blockers.join('；') }}</div>
          </div>
          <el-empty v-if="!planned.length" description="暂无待执行指令" :image-size="70" />
        </div>

        <div class="panel">
          <div class="panel-head"><span class="panel-title">执行中 / 已暂停</span><span class="panel-sub">暂停后恢复仍按绝对时间窗口执行</span></div>
          <div v-for="o in active" :key="o.id" class="limit-card" :class="{sel: selectedId === o.id}" @click="selectOrder(o)">
            <div><b>{{ o.orderNo }}</b><el-tag size="small" :type="tagType(o.status)" style="margin-left:8px">{{ o.statusName }}</el-tag></div>
            <div>{{ scopeText(o) }} · 分摊 {{ o.allocatedTotalKw }}/{{ o.targetLimitKw }} kW · 优先级 {{ o.priority }}</div>
            <div>{{ o.startLocal }} ~ {{ o.endLocal }}</div>
          </div>
          <el-empty v-if="!active.length" description="暂无执行中指令" :image-size="70" />
        </div>

        <div class="panel">
          <div class="panel-head"><span class="panel-title">已结束 / 已取消</span><span class="panel-sub">完整轨迹保留</span></div>
          <div v-for="o in closed" :key="o.id" class="limit-card" :class="{sel: selectedId === o.id}" @click="selectOrder(o)">
            <b>{{ o.orderNo }}</b>
            <el-tag size="small" :type="tagType(o.status)" style="margin-left:8px">{{ o.statusName }}</el-tag>
            <span style="margin-left:8px">{{ o.endLocal || o.cancelledLocal }}</span>
          </div>
        </div>
      </el-col>

      <el-col :span="15">
        <div class="panel" v-if="selected">
          <el-tabs v-model="detailTab">
            <el-tab-pane label="指令与轨迹" name="order">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="指令编号">{{ selected.orderNo }}</el-descriptions-item>
                <el-descriptions-item label="状态"><el-tag :type="tagType(selected.status)">{{ selected.statusName }}</el-tag></el-descriptions-item>
                <el-descriptions-item label="目标上限">{{ selected.targetLimitKw }} kW</el-descriptions-item>
                <el-descriptions-item label="适用范围">{{ selected.targetScopeName }}（{{ selected.turbineText }}）</el-descriptions-item>
                <el-descriptions-item label="分摊取舍">{{ selected.allocationModeName }}</el-descriptions-item>
                <el-descriptions-item label="重叠处理">{{ selected.overlapModeName }} · 优先级 {{ selected.priority }}</el-descriptions-item>
                <el-descriptions-item label="开始时间">{{ selected.startLocal }} {{ selected.startTz }}</el-descriptions-item>
                <el-descriptions-item label="结束时间">{{ selected.endLocal }} {{ selected.endTz }}</el-descriptions-item>
                <el-descriptions-item label="解除条件" :span="2">{{ selected.releaseCondition || '—' }}</el-descriptions-item>
                <el-descriptions-item label="值班人员">{{ selected.dutyOperator }}</el-descriptions-item>
                <el-descriptions-item label="分摊确认人">{{ selected.confirmedBy || '—' }}</el-descriptions-item>
                <el-descriptions-item label="备注" :span="2">{{ selected.note || '—' }}</el-descriptions-item>
              </el-descriptions>

              <div class="action-row">
                <el-button size="small" v-if="selected.status === 'PLANNED'" @click="previewAllocation">预分摊</el-button>
                <el-button size="small" type="primary" v-if="selected.status === 'PLANNED'" @click="doAction('start', '已开始执行')">开始执行</el-button>
                <el-button size="small" type="warning" v-if="selected.status === 'EXECUTING'" @click="doAction('pause', '已暂停')">暂停</el-button>
                <el-button size="small" type="primary" v-if="selected.status === 'PAUSED'" @click="doAction('resume', '已恢复')">恢复</el-button>
                <el-button size="small" v-if="['EXECUTING','PAUSED'].includes(selected.status)" @click="reallocate">按机位状态刷新分摊</el-button>
                <el-button size="small" type="success" v-if="['EXECUTING','PAUSED'].includes(selected.status)" @click="doAction('end', '已结束', true)">登记解除并结束</el-button>
                <el-button size="small" type="info" v-if="selected.status !== 'ENDED' && selected.status !== 'CANCELLED'" @click="doAction('cancel', '已取消')">取消</el-button>
              </div>
              <el-alert v-if="['EXECUTING','PAUSED'].includes(selected.status)" type="warning" :closable="false" show-icon style="margin: 8px 0">
                <el-input v-model="statusAction.releaseResult" size="small" placeholder="结束前填写解除条件核对结果 / 调度解除令编号" />
              </el-alert>

              <el-timeline style="margin-top: 12px">
                <el-timeline-item v-for="l in selected.logs" :key="l.id" :timestamp="`${l.createdLocal} ${l.createdTz || ''}`">
                  <b>{{ l.actionName }}</b>
                  <span v-if="l.operator"> · {{ l.operator }}</span>
                  <div>{{ l.detail }}</div>
                </el-timeline-item>
              </el-timeline>
            </el-tab-pane>

            <el-tab-pane label="机位分摊" name="allocation">
              <el-alert
                :title="`可运行容量 ${selected.runnableCapacityKw} kW；分摊合计 ${selected.allocatedTotalKw} kW；场站目标 ${selected.targetLimitKw} kW`"
                :type="selected.allocationWithinTarget ? 'success' : 'error'" :closable="false" show-icon style="margin-bottom:8px"
              />
              <el-alert v-if="selected.allocationMode === 'MANUAL'" type="info" show-icon :closable="false" style="margin-bottom:8px"
                title="当前取舍：由值班人员逐台确认。必须逐台填写，且合计不得超过场站目标。" />
              <el-alert v-else type="info" show-icon :closable="false" style="margin-bottom:8px"
                title="当前取舍：按额定容量比例分摊。后台按执行前当前并网机位和额定功率计算，停运/检修/故障机位不占额度。" />
              <el-table :data="allocationRows" size="small">
                <el-table-column prop="unitCode" label="机位" width="80" />
                <el-table-column prop="unitName" label="名称" width="100" />
                <el-table-column prop="turbineStatusName" label="执行前状态" width="110" />
                <el-table-column prop="ratedPowerKw" label="额定 kW" width="100" />
                <el-table-column label="上限 kW" width="150">
                  <template #default="{ row }">
                    <el-input-number v-if="row.runnable && selected.status === 'PLANNED' && selected.allocationMode === 'MANUAL'"
                      v-model="manualLimits[row.turbineId]" :min="0" :max="Number(row.ratedPowerKw)" :step="10" size="small" />
                    <b v-else :class="{ 'danger-text': row.latestExceeded }">{{ row.limitKw }}</b>
                  </template>
                </el-table-column>
                <el-table-column prop="latestPowerKw" label="最近功率" width="100" />
                <el-table-column label="结论" width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.runnable ? 'success' : 'info'" size="small">{{ row.allocationStateName }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="note" label="说明" />
              </el-table>
              <div class="action-row" v-if="selected.status === 'PLANNED'">
                <span v-if="selected.allocationMode === 'MANUAL'" class="panel-sub">手动合计：{{ manualTotal(selected) }} kW</span>
                <el-button size="small" type="primary" @click="confirmAllocation">确认分摊并写入后台</el-button>
              </div>
            </el-tab-pane>

            <el-tab-pane label="偏差处置" name="deviation">
              <el-table :data="selectedDeviations" size="small" max-height="430">
                <el-table-column prop="recordLocal" label="记录时刻" width="140" />
                <el-table-column prop="scopeName" label="范围" width="70" />
                <el-table-column prop="unitCode" label="机位" width="70" />
                <el-table-column prop="actualKw" label="实际 kW" width="90" />
                <el-table-column prop="limitKw" label="限值 kW" width="90" />
                <el-table-column prop="exceededKw" label="超出" width="80" />
                <el-table-column label="类型" width="110">
                  <template #default="{ row }"><el-tag size="small" :type="row.deviationType === 'PERSISTENT' ? 'danger' : 'warning'">{{ row.deviationTypeName }}</el-tag></template>
                </el-table-column>
                <el-table-column prop="statusName" label="状态" width="90" />
                <el-table-column label="版本 / 原判断" width="180">
                  <template #default="{ row }">
                    v{{ row.revisionNo }}
                    <div v-if="row.supersedesDeviationId" class="danger-text">
                      原值 {{ row.originalActualKw }} / {{ row.originalLimitKw }}，{{ row.originalDeviationTypeName }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="220" fixed="right">
                  <template #default="{ row }">
                    <el-button v-if="row.status === 'OPEN'" link type="primary" @click="deviationAction(row, 'handle', '处置')">处置</el-button>
                    <el-button v-if="row.status === 'HANDLED'" link type="primary" @click="deviationAction(row, 'review', '复核')">复核</el-button>
                    <el-button v-if="row.status === 'REVIEWED'" link type="success" @click="deviationAction(row, 'release', '解除')">解除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="重叠关系" name="overlap">
              <el-alert type="error" show-icon :closable="false" style="margin-bottom:8px"
                title="同一时刻多条有效指令时，重叠机位后台生效限值取最小值；显式替代必须覆盖旧令全部机位且逐台不宽松、结束不早于旧令。" />
              <el-table :data="selected.overlaps" size="small">
                <el-table-column prop="orderNo" label="重叠指令" width="150" />
                <el-table-column prop="statusName" label="状态" width="90" />
                <el-table-column prop="priority" label="优先级" width="80" />
                <el-table-column prop="coverage" label="覆盖关系" width="130" />
                <el-table-column prop="relationName" label="处理" width="110" />
                <el-table-column prop="effect" label="执行效果" />
              </el-table>
              <el-empty v-if="!selected.overlaps.length" description="同风场时间窗口内无其他有效指令" :image-size="90" />
            </el-tab-pane>
          </el-tabs>
        </div>
        <el-empty v-else description="请选择左侧指令" />
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" title="登记电网限功率指令" width="760px">
      <el-form :model="form" label-width="110px" size="small">
        <el-row :gutter="10">
          <el-col :span="12"><el-form-item label="指令编号"><el-input v-model="form.orderNo" placeholder="留空自动生成 GL-..." /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="值班人员"><el-input v-model="form.dutyOperator" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="目标上限 kW"><el-input-number v-model="form.targetLimitKw" :min="1" :step="100" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="优先级"><el-input-number v-model="form.priority" :min="1" :max="999" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="开始时间"><el-input v-model="form.startTime" placeholder="yyyy-MM-ddTHH:mm" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="结束时间"><el-input v-model="form.endTime" placeholder="yyyy-MM-ddTHH:mm" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="适用机位">
              <el-radio-group v-model="form.targetScope">
                <el-radio-button label="FULL">全场站</el-radio-button>
                <el-radio-button label="PART">指定机位</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="重叠处理">
              <el-radio-group v-model="form.overlapMode">
                <el-radio-button label="COEXIST">并行取严</el-radio-button>
                <el-radio-button label="SUPERSEDE">显式替代</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="适用机位" v-if="form.targetScope === 'PART'">
          <el-checkbox-group v-model="form.turbineIds">
            <el-checkbox v-for="t in turbines" :key="t.id" :label="t.id" :disabled="t.status !== 'ONLINE'">
              {{ t.unitCode }} {{ t.statusName }} / 额定 {{ t.ratedPowerKw }}kW
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-alert v-if="form.targetScope === 'FULL'" type="info" :closable="false" show-icon
          title="全场站指令也只给执行前并网机位分摊；检修、故障、停运机位自动排除，不占用目标额度。" />
        <el-form-item label="分摊方式">
          <el-radio-group v-model="form.allocationMode">
            <el-radio label="PROPORTIONAL">按额定容量比例分摊（后台计算）</el-radio>
            <el-radio label="MANUAL">由值班人员逐台确认</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="解除条件"><el-input v-model="form.releaseCondition" type="textarea" :rows="2" placeholder="如：接调度 GL-REL-... 解除令，经值长复核并确认全场出力恢复" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.limit-card { border: 1px solid #e5e9f2; border-radius: 8px; padding: 8px 10px; margin: 8px 0; cursor: pointer; background: #fff; }
.limit-card.sel { border-color: #e2483d; box-shadow: 0 0 0 2px rgba(226,72,61,.08); }
.limit-card div { line-height: 1.7; color: #4b5565; font-size: 12px; }
.action-row { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.danger-text { color: #e2483d; font-weight: 600; }
</style>
