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

const COLUMNS = [
  { value: 'PENDING', label: '待派' },
  { value: 'WORKING', label: '作业中' },
  { value: 'CHECKING', label: '待验收' },
  { value: 'CLOSED', label: '已闭环' }
]

const MAINT_TYPES = [
  { value: 'ROUTINE', label: '定期维护' },
  { value: 'TEMPORARY', label: '临时检修' },
  { value: 'EMERGENCY', label: '紧急抢修' },
  { value: 'RETROFIT', label: '技术改造' },
  { value: 'OVERHAUL', label: '大修' }
]

const orders = ref([])
const turbines = ref([])
const filterTurbineId = ref(null)
const dialog = ref(false)
const dragId = ref(null)
const overKey = ref('')
const newItem = ref({})
// 当前展开隔离票面板的工单 id；同一时刻只展开一张，避免重复确认入口过多
const openTicketOrder = ref(null)

const form = ref(emptyForm())

function emptyForm () {
  return {
    orderNo: 'WO-2026-00' + (10 + Math.floor(Math.random() * 80)),
    turbineId: null,
    maintType: 'ROUTINE',
    title: '',
    crew: '',
    faultDesc: '',
    note: ''
  }
}

const visible = computed(() => {
  if (!filterTurbineId.value) return orders.value
  return orders.value.filter((o) => o.turbineId === filterTurbineId.value)
})

const grouped = computed(() => {
  const map = {}
  for (const col of COLUMNS) map[col.value] = []
  for (const o of visible.value) {
    if (map[o.status]) map[o.status].push(o)
  }
  return map
})

const summary = computed(() => {
  let total = 0
  let done = 0
  for (const o of visible.value) {
    total += o.itemTotal || 0
    done += o.itemDone || 0
  }
  return { total, done, rate: total ? Math.round((done * 100) / total) : 0 }
})

function ringDash (progress) {
  const c = 2 * Math.PI * 16
  return `${(c * (progress || 0)).toFixed(1)} ${c.toFixed(1)}`
}

async function load () {
  orders.value = await api.workorder.kanban(props.farmId)
  turbines.value = await api.turbine.grid(props.farmId)
}

async function onDrop (status) {
  overKey.value = ''
  const id = dragId.value
  dragId.value = null
  if (!id) return
  const order = orders.value.find((o) => o.id === id)
  if (!order || order.status === status) return
  try {
    await api.workorder.status(id, status)
    ElMessage.success(`${order.orderNo} 已流转到 ${COLUMNS.find((c) => c.value === status).label}`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  }
}

async function advance (order) {
  try {
    await api.workorder.status(order.id, order.nextStatus)
    ElMessage.success(`${order.orderNo} 已推进到 ${order.nextStatusName}`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggleItem (item) {
  try {
    await api.workorder.updateItem(item.id, { done: !item.done, result: item.done ? null : (item.result || 'PASS') })
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function setResult (item, result) {
  try {
    await api.workorder.updateItem(item.id, { done: true, result })
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function addItem (order) {
  const name = newItem.value[order.id]
  if (!name) { ElMessage.warning('请填写作业项名称'); return }
  try {
    await api.workorder.createItem({ workOrderId: order.id, itemName: name })
    newItem.value[order.id] = ''
    ElMessage.success('作业项已添加')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submit () {
  try {
    await api.workorder.create(form.value)
    ElMessage.success('检修工单已创建')
    dialog.value = false
    form.value = emptyForm()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeOrder (order) {
  await ElMessageBox.confirm(`确认删除 ${order.orderNo}？`, '删除工单', { type: 'warning' })
  try {
    await api.workorder.remove(order.id)
    ElMessage.success('工单已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function toggleTicket (order) {
  openTicketOrder.value = openTicketOrder.value === order.id ? null : order.id
}

onMounted(load)
watch(() => props.farmId, () => { filterTurbineId.value = null; load() })
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">检修工单</span>
      <span class="view-tip">横向 kanban 四列拖动换列；作业项全勾并有结果才能提验收，跳阶段会被拦截</span>
      <el-button size="small" style="margin-left: auto" @click="router.push('/turbine')">← 机位总览</el-button>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">工单 {{ visible.length }}</span>
      <span class="chip">作业项 {{ summary.done }}/{{ summary.total }}</span>
      <span class="chip">完成率 {{ summary.rate }}%</span>
      <el-select v-model="filterTurbineId" size="small" clearable placeholder="全部机位" style="width: 220px">
        <el-option v-for="t in turbines" :key="t.id" :value="t.id" :label="`${t.unitCode} ${t.unitName}`" />
      </el-select>
      <el-button size="small" type="primary" @click="dialog = true">新建工单</el-button>
    </div>

    <div class="kanban">
      <div
        v-for="col in COLUMNS"
        :key="col.value"
        class="kanban-col"
        :class="{ over: overKey === col.value }"
        @dragover.prevent="overKey = col.value"
        @dragleave="overKey === col.value && (overKey = '')"
        @drop.prevent="onDrop(col.value)"
      >
        <div class="kanban-col-head">
          <span class="kanban-col-title">{{ col.label }}</span>
          <span class="kanban-col-tag">{{ grouped[col.value].length }} 张</span>
        </div>

        <div
          v-for="o in grouped[col.value]"
          :key="o.id"
          class="wo-card"
          draggable="true"
          @dragstart="dragId = o.id"
          @dragend="dragId = null"
        >
          <div style="display: flex; align-items: flex-start; gap: 8px">
            <div style="flex: 1">
              <div class="wo-no">{{ o.orderNo }}</div>
              <div class="wo-title">{{ o.title }}</div>
              <div class="wo-meta">
                <span class="wo-badge" :style="{ background: o.maintTypeColor }">{{ o.maintTypeName }}</span>
                <span>{{ o.unitCode }} · {{ o.farmName }}</span>
                <span>{{ o.crew || '未派班' }}</span>
              </div>
              <div class="wo-meta">计划 {{ o.planLocal || '—' }}（{{ o.planTz || '—' }}）</div>
            </div>
            <div class="ring">
              <svg width="42" height="42">
                <circle cx="21" cy="21" r="16" fill="none" stroke="#e0f2f1" stroke-width="5" />
                <circle cx="21" cy="21" r="16" fill="none" stroke="#00838f" stroke-width="5"
                        :stroke-dasharray="ringDash(o.progress)" />
              </svg>
              <b>{{ Math.round((o.progress || 0) * 100) }}%</b>
            </div>
          </div>

          <!-- 安全隔离票状态条：未隔离不能作业、未恢复不能闭环 -->
          <div class="ticket-bar" @click.stop="toggleTicket(o)">
            <template v-if="o.ticket">
              <span class="tb-lamp" :style="{ background: o.ticket.statusColor }"></span>
              <b>隔离票 {{ o.ticket.ticketNo }}</b>
              <el-tag size="small" effect="dark" :style="{ background: o.ticket.statusColor, borderColor: o.ticket.statusColor }">
                {{ o.ticket.statusName }}
              </el-tag>
              <span class="tb-text">责任人 {{ o.ticket.owner || '—' }}</span>
              <span class="tb-text">隔离点 {{ o.ticket.isolatedCount }}/{{ o.ticket.pointTotal }}</span>
              <span v-if="o.ticket.invalidCount > 0" class="tb-warn">⚠ 失效 {{ o.ticket.invalidCount }}</span>
              <span v-if="o.ticket.templatePendingCount > 0 && ['DRAFT','CONFIRMING'].includes(o.ticket.status)"
                    class="tb-warn">⚠ 模板新风险 {{ o.ticket.templatePendingCount }}</span>
              <span class="tb-link">{{ openTicketOrder === o.id ? '收起 ▲' : '处理 / 记录 ▼' }}</span>
            </template>
            <template v-else>
              <span class="tb-lamp" style="background: #8b95a5"></span>
              <b>无安全隔离票</b>
              <span class="tb-text" v-if="o.status !== 'CLOSED'">作业前必须先完成隔离确认</span>
              <span class="tb-link">建票 ▼</span>
            </template>
          </div>

          <!-- 隔离票操作面板 -->
          <IsolationTicketPanel
            v-if="openTicketOrder === o.id"
            :order="o"
            :work-order-id="o.id"
            @changed="load"
            @click.stop
          />

          <!-- 作业项勾选清单 -->
          <div style="margin-top: 8px">
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

            <div style="display: flex; gap: 6px; margin-top: 8px">
              <el-input v-model="newItem[o.id]" size="small" placeholder="新增作业项" style="flex: 1" />
              <el-button size="small" @click="addItem(o)">添加</el-button>
            </div>
          </div>

          <div style="display: flex; gap: 6px; margin-top: 9px">
            <el-button size="small" type="primary" :disabled="!o.nextStatus" @click="advance(o)">
              推进到 {{ o.nextStatusName || '终态' }}
            </el-button>
            <div v-if="o.nextStatus === 'WORKING' && (!o.ticket || !['ISOLATED','RESTORING'].includes(o.ticket.status))"
                 class="gate-hint">未隔离不能作业</div>
            <div v-if="o.nextStatus === 'CLOSED' && (!o.ticket || o.ticket.status !== 'RESTORED')"
                 class="gate-hint">未复电恢复不能闭环</div>
            <el-button size="small" type="danger" plain @click="removeOrder(o)">删除</el-button>
          </div>
        </div>

        <div class="empty" v-if="!grouped[col.value].length">拖到这里</div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建检修工单" width="560px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="工单号"><el-input v-model="form.orderNo" /></el-form-item>
        <el-form-item label="机位">
          <el-select v-model="form.turbineId" style="width: 100%">
            <el-option v-for="t in turbines" :key="t.id" :value="t.id" :label="`${t.unitCode} ${t.unitName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="检修类型">
          <el-select v-model="form.maintType">
            <el-option v-for="m in MAINT_TYPES" :key="m.value" :value="m.value" :label="m.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="检修班组"><el-input v-model="form.crew" /></el-form-item>
        <el-form-item label="缺陷描述"><el-input v-model="form.faultDesc" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ticket-bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 8px;
  padding: 5px 8px; border-radius: 6px; background: #f6f8f8; cursor: pointer; font-size: 12px }
.tb-lamp { width: 8px; height: 8px; border-radius: 50%; display: inline-block }
.tb-text { color: #6b7785 }
.tb-warn { color: #e2483d; font-weight: 600 }
.tb-link { margin-left: auto; color: #00838f; font-weight: 600 }
.gate-hint { align-self: center; color: #e2483d; font-size: 12px }
</style>
