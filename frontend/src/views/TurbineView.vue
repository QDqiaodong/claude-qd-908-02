<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'
import IsolationTicketPanel from '../components/IsolationTicketPanel.vue'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const TABS = [
  { key: 'run', label: '运行日志' },
  { key: 'maint', label: '检修工单' },
  { key: 'part', label: '备件更换' },
  { key: 'stat', label: '发电统计' }
]

const STATUS_FLOW = [
  { value: 'ONLINE', label: '并网' },
  { value: 'MAINTAIN', label: '检修' },
  { value: 'FAULT', label: '故障' },
  { value: 'STOPPED', label: '停运' }
]

const turbines = ref([])
const farms = ref([])
const parts = ref([])

const overlay = ref(false)
const current = ref(null)
const tab = ref('run')

const logs = ref([])
const orders = ref([])
const replacements = ref([])
const turbineStat = ref(null)
// 机位页检修 Tab 中展开隔离票面板的工单 id
const openTicketOrder = ref(null)

const logForm = ref(emptyLogForm())
const replaceForm = ref({ partId: null, qty: 1, operator: '', note: '' })
const dialog = ref(false)
const form = ref(emptyForm())

function emptyForm () {
  return {
    farmId: props.farmId,
    unitCode: '',
    unitName: '',
    model: '',
    ratedPowerKw: 4500,
    hubHeightM: 100,
    rotorDiameterM: 155,
    status: 'ONLINE',
    note: ''
  }
}

function emptyLogForm () {
  return { windSpeedMs: 8.5, activePowerKw: 3000, dailyEnergyKwh: 50000, windDirection: '西北', temperatureC: 18, operator: '', note: '', recordTime: '' }
}

const offsetOf = (farmId) => {
  const farm = farms.value.find((f) => f.id === farmId)
  return farm ? farm.offsetText : '+08:00'
}

function pad (n) { return String(n).padStart(2, '0') }
function nowIso (offset) {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00${offset || '+08:00'}`
}

const onlineCount = computed(() => turbines.value.filter((t) => t.status === 'ONLINE').length)
const faultCount = computed(() => turbines.value.filter((t) => t.status === 'FAULT').length)
const todayEnergy = computed(() => turbines.value.reduce((sum, t) => sum + Number(t.todayEnergyKwh || 0), 0))

async function load () {
  turbines.value = await api.turbine.grid(props.farmId)
  if (!farms.value.length) farms.value = await api.farm.vo()
  if (!parts.value.length) parts.value = await api.part.vo()
}

async function openCard (turbine) {
  current.value = turbine
  tab.value = 'run'
  overlay.value = true
  logForm.value = emptyLogForm()
  logForm.value.recordTime = nowIso(offsetOf(turbine.farmId))
  replaceForm.value = { partId: null, qty: 1, operator: '', note: '' }
  await loadOverlay()
}

async function loadOverlay () {
  if (!current.value) return
  const id = current.value.id
  const [l, o, r] = await Promise.all([
    api.runlog.vo(id),
    api.workorder.vo(id),
    api.part.replacementVOs(null, id)
  ])
  logs.value = l
  orders.value = o
  replacements.value = r
  const all = await api.stats.byTurbine(props.farmId)
  turbineStat.value = all.find((x) => x.turbineId === id) || null
}

async function changeStatus (status) {
  try {
    await api.turbine.status(current.value.id, status)
    ElMessage.success(`${current.value.unitCode} 已转为${STATUS_FLOW.find((s) => s.value === status).label}`)
    await loadOverlay()
    await load()
    const refreshed = turbines.value.find((t) => t.id === current.value.id)
    if (refreshed) current.value = refreshed
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitLog () {
  try {
    await api.runlog.create({ ...logForm.value, turbineId: current.value.id })
    ElMessage.success('运行日志已登记')
    logForm.value = emptyLogForm()
    logForm.value.recordTime = nowIso(offsetOf(current.value.farmId))
    await loadOverlay()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggleItem (item) {
  try {
    await api.workorder.updateItem(item.id, {
      done: !item.done,
      result: item.done ? null : (item.result || 'PASS')
    })
    await loadOverlay()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function setResult (item, result) {
  try {
    await api.workorder.updateItem(item.id, { done: true, result })
    await loadOverlay()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function advance (order) {
  try {
    if (!order.nextStatus) return
    await api.workorder.status(order.id, order.nextStatus)
    ElMessage.success(`${order.orderNo} 已推进到 ${order.nextStatusName}`)
    await loadOverlay()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReplace () {
  try {
    await api.part.replace({
      partId: replaceForm.value.partId,
      turbineId: current.value.id,
      qty: replaceForm.value.qty,
      operator: replaceForm.value.operator,
      note: replaceForm.value.note
    })
    ElMessage.success('更换登记已完成')
    replaceForm.value = { partId: null, qty: 1, operator: '', note: '' }
    parts.value = await api.part.vo()
    await loadOverlay()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submit () {
  try {
    await api.turbine.create({ ...form.value, farmId: props.farmId })
    ElMessage.success('机位已创建')
    dialog.value = false
    form.value = emptyForm()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeTurbine (turbine) {
  await ElMessageBox.confirm(`确认删除机位 ${turbine.unitCode}？`, '删除机位', { type: 'warning' })
  try {
    await api.turbine.remove(turbine.id)
    ElMessage.success('机位已删除')
    overlay.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function goModule (path) {
  overlay.value = false
  router.push({ path, query: { turbineId: current.value.id } })
}

function ringDash (progress) {
  const c = 2 * Math.PI * 16
  return `${(c * (progress || 0)).toFixed(1)} ${c.toFixed(1)}`
}

// 隔离票任何变更后：刷新覆盖层（工单/票/读数）与外层机位网格（状态、隔离锁、能否并网/登记）
async function onIsolationChanged () {
  await loadOverlay()
  await load()
  const refreshed = turbines.value.find((t) => t.id === current.value?.id)
  if (refreshed) current.value = refreshed
}

onMounted(load)
watch(() => props.farmId, () => {
  overlay.value = false
  load()
})
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">机位总览</span>
      <span class="view-tip">
        主区大卡片网格即导航：点卡片打开全屏详情覆盖层，卡片右上角「进入XX」切换模块
      </span>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">机位 {{ turbines.length }}</span>
      <span class="chip">并网 {{ onlineCount }}</span>
      <span class="chip" :class="{ bad: faultCount > 0 }">故障 {{ faultCount }}</span>
      <span class="chip">今日发电 {{ Math.round(todayEnergy).toLocaleString() }} kWh</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="dialog = true">新增机位</el-button>
    </div>

    <div class="turbine-grid">
      <div
        v-for="t in turbines"
        :key="t.id"
        class="turbine-card"
        :style="{ '--tc-color': t.statusColor }"
        @click="openCard(t)"
      >
        <div class="tc-top">
          <div>
            <div class="tc-code">{{ t.unitCode }}</div>
            <div class="tc-name">{{ t.unitName }} · {{ t.model }}</div>
            <div class="tc-model">额定 {{ Number(t.ratedPowerKw).toLocaleString() }} kW</div>
          </div>
          <div class="tc-lamp">
            <i class="tc-dot" :class="{ blink: t.status === 'FAULT' || t.status === 'MAINTAIN' }"></i>
            {{ t.statusName }}
          </div>
        </div>

        <div v-if="t.isolationLocked" class="tc-lock-banner">
          🔒 {{ t.activeTicketNo }} {{ t.activeTicketStatusName }} · 责任人 {{ t.activeTicketOwner }} · 禁登记 / 禁并网
        </div>
        <div v-else-if="t.activeTicketNo && t.activeTicketStatus === 'DRAFT'" class="tc-lock-banner draft">
          隔离票 {{ t.activeTicketNo }} 草稿 · 责任人 {{ t.activeTicketOwner }}
        </div>

        <div class="tc-metrics">
          <div class="tc-metric">
            <b>{{ Number(t.lastPowerKw || 0).toLocaleString() }}</b>
            <span>当前功率 kW</span>
          </div>
          <div class="tc-metric">
            <b>{{ Number(t.todayEnergyKwh || 0).toLocaleString() }}</b>
            <span>今日发电量 kWh</span>
          </div>
        </div>

        <div class="tc-load"><i :style="{ width: Math.round((t.loadRate || 0) * 100) + '%' }"></i></div>

        <div class="tc-actions">
          <button class="tc-enter" @click.stop="openCard(t); tab = 'run'">进入运行</button>
          <button class="tc-enter" @click.stop="openCard(t); tab = 'maint'">进入检修</button>
          <button class="tc-enter" @click.stop="openCard(t); tab = 'part'">进入备件</button>
          <button class="tc-enter" @click.stop="openCard(t); tab = 'stat'">进入统计</button>
        </div>

        <div class="tc-foot">{{ t.farmCode }} · {{ t.timeZoneName }} {{ t.offsetText }}</div>
      </div>
    </div>

    <div class="empty" v-if="!turbines.length">该风场暂无机位</div>

    <!-- ================= 全屏详情覆盖层 ================= -->
    <div class="overlay" v-if="overlay && current" @click.self="overlay = false">
      <div class="overlay-panel">
        <div class="overlay-head">
          <div>
            <div class="overlay-code">{{ current.unitCode }}</div>
            <div class="overlay-sub">
              {{ current.farmName }} · {{ current.unitName }} · {{ current.model }} ·
              额定 {{ Number(current.ratedPowerKw).toLocaleString() }} kW ·
              <span class="tz-tag">{{ current.timeZoneName }} {{ current.offsetText }}</span>
            </div>
          </div>
          <div style="margin-left: 12px; display: flex; gap: 6px; flex-wrap: wrap">
            <el-button
              v-for="s in STATUS_FLOW"
              :key="s.value"
              size="small"
              :type="current.status === s.value ? 'primary' : 'default'"
              :disabled="s.value === 'ONLINE' && current.activeTicketNo"
              @click="changeStatus(s.value)"
            >{{ s.label }}</el-button>
          </div>
          <button class="overlay-close" @click="overlay = false">✕</button>
        </div>

        <div v-if="current.activeTicketNo" class="overlay-lock"
             :class="{ locked: current.isolationLocked }">
          <template v-if="current.isolationLocked">
            🔒 机位被隔离票 <b>{{ current.activeTicketNo }}</b>（{{ current.activeTicketStatusName }}）锁定，
            责任人 <b>{{ current.activeTicketOwner }}</b>：期间禁止登记发电读数、禁止并网；
            须按工单完成验收并逆序复电恢复
          </template>
          <template v-else>
            隔离票 <b>{{ current.activeTicketNo }}</b> 为草稿（责任人 {{ current.activeTicketOwner }}），
            尚未执行隔离措施
          </template>
        </div>

        <div class="overlay-tabs">
          <button
            v-for="t in TABS"
            :key="t.key"
            class="otab"
            :class="{ active: tab === t.key }"
            @click="tab = t.key"
          >{{ t.label }}</button>
          <el-button size="small" type="danger" plain style="margin-left: auto" @click="removeTurbine(current)">删除机位</el-button>
        </div>

        <div class="overlay-body">
          <!-- ---------- 运行日志 ---------- -->
          <div v-if="tab === 'run'">
            <div class="panel">
              <div class="panel-head">
                <span class="panel-title">登记一条读数</span>
                <span class="panel-sub">故障机位不允许登记；隔离确认中 / 已隔离 / 复电中禁止登记；同一时刻不允许重复登记</span>
              </div>
              <el-alert
                v-if="current.isolationLocked"
                type="error" show-icon :closable="false" style="margin-bottom: 8px"
                :title="`机位隔离锁定中（${current.activeTicketNo} · ${current.activeTicketStatusName}），安全措施未恢复，禁止登记发电读数`"
                description="请先在「检修」页完成隔离票复电恢复，再恢复并网登记读数。"
              />
              <el-form :inline="true" size="small" :disabled="!!current.isolationLocked">
                <el-form-item label="记录时刻">
                  <el-input v-model="logForm.recordTime" placeholder="2026-09-28T08:00:00+08:00" style="width: 220px" />
                </el-form-item>
                <el-form-item label="风速 m/s"><el-input-number v-model="logForm.windSpeedMs" :min="0" :max="40" :step="0.1" /></el-form-item>
                <el-form-item label="有功 kW"><el-input-number v-model="logForm.activePowerKw" :min="0" :step="10" /></el-form-item>
                <el-form-item label="日发电 kWh"><el-input-number v-model="logForm.dailyEnergyKwh" :min="1" :step="100" /></el-form-item>
                <el-form-item label="风向"><el-input v-model="logForm.windDirection" style="width: 90px" /></el-form-item>
                <el-form-item label="值班员"><el-input v-model="logForm.operator" style="width: 110px" /></el-form-item>
                <el-form-item><el-button type="primary" @click="submitLog">登记</el-button></el-form-item>
              </el-form>
            </div>

            <div class="panel">
              <div class="panel-head"><span class="panel-title">最近读数</span><span class="panel-sub">时刻按场站本地时区还原</span></div>
              <el-table :data="logs" size="small" max-height="260">
                <el-table-column prop="recordLocal" label="记录时刻" width="150" />
                <el-table-column prop="recordTz" label="时区列" width="80" />
                <el-table-column prop="windSpeedMs" label="风速" width="80" />
                <el-table-column prop="activePowerKw" label="有功 kW" width="100" />
                <el-table-column prop="dailyEnergyKwh" label="日发电 kWh" width="120" />
                <el-table-column prop="windDirection" label="风向" width="80" />
                <el-table-column prop="operator" label="值班员" width="110" />
                <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
              </el-table>
            </div>
          </div>

          <!-- ---------- 检修工单 ---------- -->
          <div v-if="tab === 'maint'">
            <div v-for="o in orders" :key="o.id" class="panel" style="margin-bottom: 10px">
              <div class="panel-head">
                <span class="panel-title">{{ o.orderNo }}</span>
                <span class="wo-badge" :style="{ background: o.maintTypeColor }">{{ o.maintTypeName }}</span>
                <span class="panel-sub">{{ o.title }}</span>
                <el-tag v-if="o.ticket" size="small" effect="dark"
                        :style="{ background: o.ticket.statusColor, borderColor: o.ticket.statusColor }">
                  {{ o.ticket.ticketNo }} {{ o.ticket.statusName }}
                </el-tag>
                <el-tag v-else-if="o.status !== 'CLOSED'" size="small" type="info">无隔离票</el-tag>
                <div class="ring" style="margin-left: auto">
                  <svg width="42" height="42">
                    <circle cx="21" cy="21" r="16" fill="none" stroke="#e0f2f1" stroke-width="5" />
                    <circle cx="21" cy="21" r="16" fill="none" stroke="#00838f" stroke-width="5"
                            :stroke-dasharray="ringDash(o.progress)" />
                  </svg>
                  <b>{{ Math.round((o.progress || 0) * 100) }}%</b>
                </div>
              </div>
              <div class="wo-meta" style="margin-bottom: 8px">
                <span>{{ o.statusName }}</span>
                <span>班组 {{ o.crew || '—' }}</span>
                <span>计划 {{ o.planLocal || '—' }}（{{ o.planTz }}）</span>
                <span v-if="o.acceptResult">
                  验收：<b :style="{ color: o.acceptResult === 'PASS' ? '#2e9e5b' : '#e2483d' }">
                    {{ o.acceptResultName }} {{ o.acceptPerson }} {{ o.acceptLocal }}
                  </b>
                </span>
              </div>

              <div v-for="it in o.items" :key="it.id" class="item-row" :class="{ done: it.done }">
                <el-checkbox :model-value="!!it.done" @change="toggleItem(it)" />
                <div class="ir-name">
                  {{ it.itemSeq }}. {{ it.itemName }}
                  <div class="ir-std">{{ it.standard }}</div>
                </div>
                <el-radio-group :model-value="it.result" size="small" @change="(v) => setResult(it, v)">
                  <el-radio-button value="PASS">合格</el-radio-button>
                  <el-radio-button value="FAIL">不合格</el-radio-button>
                  <el-radio-button value="SKIP">免检</el-radio-button>
                </el-radio-group>
              </div>
              <div class="empty" v-if="!o.items.length">还没有作业项</div>

              <!-- 安全隔离票闭环：责任人、待办项、完整变更记录都在这里 -->
              <div style="margin-top: 6px">
                <el-button size="small" :type="openTicketOrder === o.id ? 'default' : 'warning'" plain
                           @click="openTicketOrder = openTicketOrder === o.id ? null : o.id">
                  {{ openTicketOrder === o.id ? '收起安全隔离票' : (o.ticket ? `安全隔离票 · ${o.ticket.statusName}` : '创建安全隔离票') }}
                </el-button>
                <el-button
                  size="small"
                  type="primary"
                  :disabled="!o.nextStatus"
                  @click="advance(o)"
                >推进到 {{ o.nextStatusName || '终态' }}</el-button>
                <el-button size="small" @click="goModule('/maint')">进检修模块</el-button>
                <span v-if="o.nextStatus === 'WORKING' && (!o.ticket || !['ISOLATED','RESTORING'].includes(o.ticket.status))"
                      style="color: #e2483d; font-size: 12px; margin-left: 6px">未隔离不能作业</span>
                <span v-if="o.nextStatus === 'CLOSED' && (!o.ticket || o.ticket.status !== 'RESTORED')"
                      style="color: #e2483d; font-size: 12px; margin-left: 6px">未复电恢复不能闭环</span>
              </div>

              <IsolationTicketPanel
                v-if="openTicketOrder === o.id"
                :order="o"
                :work-order-id="o.id"
                @changed="onIsolationChanged"
              />
            </div>
            <div class="empty" v-if="!orders.length">该机位暂无检修工单</div>
          </div>

          <!-- ---------- 备件更换 ---------- -->
          <div v-if="tab === 'part'">
            <div class="panel">
              <div class="panel-head"><span class="panel-title">更换登记</span><span class="panel-sub">数量不得超过当前库存；低于安全库存仍可出库但会打预警标记</span></div>
              <el-form :inline="true" size="small">
                <el-form-item label="备件">
                  <el-select v-model="replaceForm.partId" placeholder="选择备件" style="width: 240px">
                    <el-option v-for="p in parts" :key="p.id" :value="p.id"
                      :label="`${p.partCode} ${p.partName}（库存 ${p.stockQty}${p.unit}）`" />
                  </el-select>
                </el-form-item>
                <el-form-item label="数量"><el-input-number v-model="replaceForm.qty" :min="1" :max="200" /></el-form-item>
                <el-form-item label="更换人"><el-input v-model="replaceForm.operator" style="width: 110px" /></el-form-item>
                <el-form-item><el-button type="primary" @click="submitReplace">登记更换</el-button></el-form-item>
              </el-form>
            </div>
            <div class="panel">
              <div class="panel-head"><span class="panel-title">该机位更换记录</span></div>
              <el-table :data="replacements" size="small" max-height="260">
                <el-table-column prop="partCode" label="备件编号" width="130" />
                <el-table-column prop="partName" label="名称" min-width="140" />
                <el-table-column prop="qty" label="数量" width="70" />
                <el-table-column prop="replaceTime" label="更换时间" width="150" />
                <el-table-column prop="operator" label="更换人" width="120" />
                <el-table-column label="预警" width="80">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.warningFlag ? 'danger' : 'success'">
                      {{ row.warningFlag ? '预警' : '正常' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="stockAfter" label="剩余库存" width="90" />
              </el-table>
            </div>
          </div>

          <!-- ---------- 发电统计 ---------- -->
          <div v-if="tab === 'stat'">
            <div class="panel" v-if="turbineStat">
              <div class="panel-head"><span class="panel-title">{{ turbineStat.unitCode }} 发电概览</span></div>
              <div class="stat-grid">
                <div class="stat-box"><b>{{ Number(turbineStat.dailyEnergyKwh).toLocaleString() }}</b><span>最近一日 kWh</span></div>
                <div class="stat-box"><b>{{ Number(turbineStat.monthlyEnergyKwh).toLocaleString() }}</b><span>本月 kWh</span></div>
                <div class="stat-box"><b>{{ Number(turbineStat.totalEnergyKwh).toLocaleString() }}</b><span>累计 kWh</span></div>
                <div class="stat-box"><b>{{ Number(turbineStat.avgPowerKw).toLocaleString() }}</b><span>平均功率 kW</span></div>
                <div class="stat-box"><b>{{ Number(turbineStat.equivalentHours) }}</b><span>等效利用小时 h</span></div>
                <div class="stat-box"><b>{{ turbineStat.logCount }}</b><span>读数条数</span></div>
              </div>
            </div>
            <div class="empty" v-else>该机位暂无发电统计</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增机位 -->
    <el-dialog v-model="dialog" title="新增机位" width="560px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="机位编号"><el-input v-model="form.unitCode" placeholder="如 A04" /></el-form-item>
        <el-form-item label="机位名称"><el-input v-model="form.unitName" placeholder="如 4 号机位" /></el-form-item>
        <el-form-item label="机型"><el-input v-model="form.model" placeholder="如 金风 GW155-4.5" /></el-form-item>
        <el-form-item label="额定功率 kW"><el-input-number v-model="form.ratedPowerKw" :min="1" :max="10000" /></el-form-item>
        <el-form-item label="轮毂高度 m"><el-input-number v-model="form.hubHeightM" :min="1" /></el-form-item>
        <el-form-item label="风轮直径 m"><el-input-number v-model="form.rotorDiameterM" :min="1" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option v-for="s in STATUS_FLOW" :key="s.value" :value="s.value" :label="s.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
