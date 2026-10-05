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
const commands = ref([])
const events = ref([])
const cases = ref([])
const selectedId = ref(null)
const showForm = ref(false)
const form = ref(emptyForm())
const actionText = ref({})
const manualLimits = ref({})
const caseDialog = ref(false)
const currentCaseId = ref(null)
const caseForm = ref(emptyCaseForm())

function emptyForm () {
  return {
    id: null,
    commandNo: '',
    targetLimitKw: 10000,
    allocationMode: 'PROPORTIONAL',
    priorityRank: 100,
    timeRange: [],
    releaseCondition: '',
    owner: '',
    note: '',
    turbineIds: []
  }
}

function emptyCaseForm () {
  return { action: '', result: 'CONFIRMED', releaseResult: 'RESOLVED', operator: '', note: '' }
}

const currentFarm = computed(() => farms.value.find((f) => f.id === props.farmId))
const offset = computed(() => currentFarm.value?.offsetText || '+08:00')
const selected = computed(() => commands.value.find((c) => c.id === selectedId.value) || null)
const pending = computed(() => commands.value.filter((c) => c.status === 'PLANNED'))
const active = computed(() => commands.value.filter((c) => c.status === 'EXECUTING' || c.status === 'PAUSED'))
const openEvents = computed(() => events.value.filter((e) => e.status !== 'SUPERSEDED'))
const openCases = computed(() => cases.value.filter((c) => c.status !== 'RELEASED'))

function nowLocalInput () {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function toIso (text) {
  if (!text) return null
  return `${text.slice(0, 10)}T${text.slice(11, 16)}:00${offset.value}`
}

function fmt (text) {
  return text ? text.slice(0, 16) : '—'
}

function num (v) { return Number(v || 0) }

function className (v) {
  return {
    LATE_ONLY: '读数迟报',
    SINGLE_EXCEED: '短时越限',
    PERSISTENT_EXCEED: '持续越限',
    CLEARED: '复核回落',
    OK: '正常',
    MISSING_DATA: '读数缺失'
  }[v] || v
}

function statusType (s) {
  return { PLANNED: 'info', EXECUTING: 'danger', PAUSED: 'warning', FINISHED: 'success', CANCELLED: 'info' }[s] || ''
}

async function load () {
  if (!props.farmId) return
  const [farmList, turbineList, commandList, eventList, caseList] = await Promise.all([
    farms.value.length ? Promise.resolve(farms.value) : api.farm.vo(),
    api.turbine.grid(props.farmId),
    api.grid.commands(props.farmId),
    api.grid.events(props.farmId),
    api.grid.cases(props.farmId)
  ])
  farms.value = farmList
  turbines.value = turbineList
  commands.value = commandList
  events.value = eventList
  cases.value = caseList
  if (!selectedId.value && commandList.length) selectedId.value = commandList[0].id
  if (selectedId.value && !commandList.some((c) => c.id === selectedId.value)) {
    selectedId.value = commandList[0]?.id || null
  }
  if (selected.value) syncManual(selected.value)
}

function syncManual (cmd) {
  const map = {}
  cmd.allocations.forEach((a) => { map[a.turbineId] = num(a.allocatedLimitKw || a.currentLimitKw) })
  manualLimits.value = map
}

function startCreate () {
  form.value = emptyForm()
  form.value.commandNo = `GL-${new Date().getFullYear()}-${String(Date.now()).slice(-6)}`
  form.value.turbineIds = turbines.value.filter((t) => t.status === 'ONLINE').map((t) => t.id)
  showForm.value = true
}

function editCommand (cmd) {
  form.value = {
    id: cmd.id,
    commandNo: cmd.commandNo,
    targetLimitKw: num(cmd.targetLimitKw),
    allocationMode: cmd.allocationMode,
    priorityRank: cmd.priorityRank,
    timeRange: [cmd.startLocal, cmd.endLocal],
    releaseCondition: cmd.releaseCondition || '',
    owner: cmd.owner || '',
    note: cmd.note || '',
    turbineIds: cmd.allocations.filter((a) => a.includedFlag).map((a) => a.turbineId)
  }
  showForm.value = true
}

async function submitCommand () {
  try {
    if (!form.value.timeRange?.length || form.value.timeRange.length !== 2) {
      ElMessage.error('请选择开始、结束时间')
      return
    }
    const payload = {
      commandNo: form.value.commandNo,
      farmId: props.farmId,
      targetLimitKw: form.value.targetLimitKw,
      allocationMode: form.value.allocationMode,
      priorityRank: form.value.priorityRank,
      startTime: toIso(form.value.timeRange[0]),
      endTime: toIso(form.value.timeRange[1]),
      releaseCondition: form.value.releaseCondition,
      owner: form.value.owner,
      note: form.value.note,
      turbineIds: form.value.turbineIds
    }
    if (form.value.id) await api.grid.update(form.value.id, payload)
    else await api.grid.create(payload)
    ElMessage.success('指令已保存，并生成启动前分摊数据')
    showForm.value = false
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function callAction (cmd, action, message, body = {}) {
  try {
    const data = await api.grid[action](cmd.id, body)
    ElMessage.success(message)
    if (data?.id) selectedId.value = data.id
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function preview (cmd) {
  await callAction(cmd, 'preview', '已按当前可运行机位额定容量重新分摊')
}

async function confirmAllocation (cmd) {
  try {
    const operator = await ElMessageBox.prompt('请输入确认值班员', '分摊确认', {
      inputValue: cmd.owner || '',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    }).then((r) => r.value)
    const body = cmd.allocationMode === 'MANUAL'
      ? { limits: manualLimits.value, operator, note: '值班人员逐台确认' }
      : { operator, note: '确认比例分摊' }
    await api.grid.confirm(cmd.id, body)
    ElMessage.success('机位分摊已确认')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

function actionDialog (cmd, action, title, label) {
  ElMessageBox.prompt(label, title, {
    inputValue: actionText.value[cmd.id] || '',
    confirmButtonText: '提交',
    cancelButtonText: '取消'
  }).then(({ value }) => {
    callAction(cmd, action, `${title}已记录`, { operator: cmd.owner || '值班人员', reason: value || title })
  }).catch(() => {})
}

async function removeCommand (cmd) {
  await ElMessageBox.confirm(`确认删除待执行指令 ${cmd.commandNo}？`, '删除', { type: 'warning' })
  await api.grid.remove(cmd.id)
  ElMessage.success('已删除')
  await load()
}

async function syncNow () {
  await api.grid.sync(props.farmId)
  ElMessage.success('已按当前绝对时刻同步')
  await load()
}

const currentCase = computed(() => cases.value.find((c) => c.id === currentCaseId.value) || null)

function openCase (c) {
  currentCaseId.value = c.id
  caseForm.value = emptyCaseForm()
  caseDialog.value = true
}

async function submitCase (stage) {
  try {
    if (!caseForm.value.operator) {
      ElMessage.error('请填写值班人员')
      return
    }
    if (stage === 'handle') {
      await api.grid.handle(currentCaseId.value, {
        action: caseForm.value.action,
        operator: caseForm.value.operator,
        note: caseForm.value.note
      })
    } else if (stage === 'review') {
      await api.grid.review(currentCaseId.value, {
        result: caseForm.value.result,
        operator: caseForm.value.operator,
        note: caseForm.value.note
      })
    } else {
      await api.grid.release(currentCaseId.value, {
        result: caseForm.value.releaseResult,
        operator: caseForm.value.operator,
        note: caseForm.value.note
      })
    }
    ElMessage.success('处置轨迹已保存，原判定不会被覆盖')
    caseDialog.value = false
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

onMounted(load)
watch(() => props.farmId, () => { selectedId.value = null; load() })
watch(selectedId, async () => {
  if (selectedId.value) {
    const fresh = await api.grid.command(selectedId.value)
    const i = commands.value.findIndex((c) => c.id === fresh.id)
    if (i >= 0) commands.value[i] = fresh
    syncManual(fresh)
  }
})
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">电网限功率指令</span>
      <span class="view-tip">后台写入分摊与偏差判定；并行指令逐机位取最严，修正读数只追加判定版本</span>
      <el-button size="small" style="margin-left:auto" @click="syncNow">同步到期状态</el-button>
      <el-button size="small" type="primary" @click="startCreate">登记指令</el-button>
    </div>

    <el-row :gutter="12">
      <el-col :span="9">
        <div class="panel">
          <div class="panel-head"><span class="panel-title">待执行 / 执行中</span><span class="panel-sub">{{ farmName }} · {{ offset }}</span></div>
          <el-table :data="commands" size="small" max-height="520" highlight-current-row @row-click="r => selectedId = r.id">
            <el-table-column prop="commandNo" label="指令编号" width="132" />
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status)">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetLimitKw" label="目标 kW" width="92" />
            <el-table-column label="窗口" width="205">
              <template #default="{ row }">
                <div>{{ fmt(row.startLocal) }}</div>
                <div>至 {{ fmt(row.endLocal) }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="priorityRank" label="优先级" width="62" />
          </el-table>
        </div>
      </el-col>

      <el-col :span="15">
        <div v-if="selected" class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">{{ selected.commandNo }} · {{ selected.statusName }}</div>
              <div class="panel-sub">目标 {{ selected.targetLimitKw }} kW；当前分摊合计 {{ selected.currentTotalKw || 0 }} kW；解除条件：{{ selected.releaseCondition || '—' }}</div>
            </div>
            <div>
              <el-button size="small" @click="editCommand(selected)" :disabled="selected.status !== 'PLANNED'">修改</el-button>
              <el-button size="small" type="danger" plain @click="removeCommand(selected)" :disabled="selected.status !== 'PLANNED'">删除</el-button>
            </div>
          </div>

          <el-alert v-for="b in selected.blockers" :key="b" :title="b" type="warning" show-icon :closable="false" style="margin: 6px 0" />
          <div class="chip-bar" style="margin: 8px 0">
            <el-tag>{{ selected.allocationModeName }}</el-tag>
            <el-tag :type="selected.withinWindow ? 'danger' : 'info'">当前{{ selected.withinWindow ? '在窗口内' : '不在窗口内' }}</el-tag>
            <el-tag>可运行额定 {{ selected.runnableRatedTotalKw || 0 }} kW</el-tag>
            <el-tag :type="num(selected.currentTotalKw) > num(selected.targetLimitKw) ? 'danger' : 'success'">不超目标</el-tag>
          </div>

          <div style="margin: 8px 0">
            <el-button size="small" @click="preview(selected)" v-if="selected.status === 'PLANNED' && selected.allocationMode === 'PROPORTIONAL'">重算比例分摊</el-button>
            <el-button size="small" type="primary" @click="confirmAllocation(selected)" :disabled="['FINISHED','CANCELLED'].includes(selected.status)">确认分摊</el-button>
            <el-button size="small" type="danger" @click="callAction(selected, 'start', '已进入执行')" :disabled="selected.status !== 'PLANNED'">开始</el-button>
            <el-button size="small" type="warning" @click="actionDialog(selected, 'pause', '暂停', '请填写暂停原因')" :disabled="selected.status !== 'EXECUTING'">暂停</el-button>
            <el-button size="small" type="success" @click="actionDialog(selected, 'resume', '恢复', '请填写恢复说明')" :disabled="selected.status !== 'PAUSED'">恢复</el-button>
            <el-button size="small" @click="actionDialog(selected, 'finish', '结束', '请填写解除条件执行结果')" :disabled="!['EXECUTING','PAUSED'].includes(selected.status)">结束</el-button>
            <el-button size="small" @click="actionDialog(selected, 'cancel', '取消', '请填写取消原因')" :disabled="['FINISHED','CANCELLED'].includes(selected.status)">取消</el-button>
          </div>

          <el-table :data="selected.allocations" size="small" max-height="260">
            <el-table-column prop="unitCode" label="机位" width="72" />
            <el-table-column prop="turbineStatusName" label="当前状态" width="82" />
            <el-table-column prop="ratedPowerKw" label="额定 kW" width="90" />
            <el-table-column label="启动分摊 kW" width="135">
              <template #default="{ row }">
                <el-input-number v-if="selected.allocationMode === 'MANUAL' && row.runnableFlag"
                  v-model="manualLimits[row.turbineId]" :min="0" :max="num(row.ratedPowerKw)" :step="50" size="small" />
                <span v-else>{{ row.allocatedLimitKw }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="currentLimitKw" label="当前额度" width="92" />
            <el-table-column prop="strictLimitKw" label="并行最严值" width="102" />
            <el-table-column prop="latestPowerKw" label="最新功率" width="90" />
            <el-table-column prop="strictCommandNos" label="最严来源" min-width="120" />
            <el-table-column prop="reason" label="状态说明" min-width="160" />
          </el-table>
        </div>
        <el-empty v-else description="请选择左侧指令" />
      </el-col>
    </el-row>

    <div class="panel" v-if="selected">
      <div class="panel-head"><span class="panel-title">重叠关系</span><span class="panel-sub">较宽松新指令不会解除较严旧限制；所有执行中指令按机位取最小值</span></div>
      <el-table :data="selected.overlaps" size="small">
        <el-table-column prop="commandNo" label="重叠指令" width="140" />
        <el-table-column prop="statusName" label="状态" width="90" />
        <el-table-column prop="relationName" label="时间关系" width="120" />
        <el-table-column prop="sharedUnitCodes" label="共享机位" min-width="160" />
        <el-table-column prop="priorityRank" label="优先级" width="70" />
        <el-table-column prop="effectiveRule" label="覆盖/取舍" min-width="280" />
      </el-table>
    </div>

    <el-row :gutter="12">
      <el-col :span="14">
        <div class="panel">
          <div class="panel-head"><span class="panel-title">偏差处置</span><span class="panel-sub">区分迟报、短时波动、持续越限；补录修正后保留旧版本</span></div>
          <el-table :data="openCases" size="small" max-height="330">
            <el-table-column prop="commandNo" label="指令" width="125" />
            <el-table-column prop="unitCode" label="机位" width="70" />
            <el-table-column label="类型" width="92">
              <template #default="{ row }"><el-tag size="small" :type="row.classification === 'PERSISTENT_EXCEED' ? 'danger' : 'warning'">{{ row.classificationName }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="overageKw" label="越限 kW" width="90" />
            <el-table-column prop="statusName" label="处置阶段" width="82" />
            <el-table-column prop="observedLocal" label="观测时刻" width="126" />
            <el-table-column label="操作" width="82" fixed="right">
              <template #default="{ row }"><el-button link type="primary" size="small" @click="openCase(row)">处理</el-button></template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="panel">
          <div class="panel-head"><span class="panel-title">最新判定事件</span><span class="panel-sub">含迟报与已替代版本</span></div>
          <el-table :data="openEvents.slice(0, 20)" size="small" max-height="330">
            <el-table-column prop="commandNo" label="指令" width="125" />
            <el-table-column prop="unitCode" label="机位" width="66" />
            <el-table-column prop="classificationName" label="结论" width="90" />
            <el-table-column prop="revisionNo" label="版本" width="55" />
            <el-table-column prop="statusName" label="状态" width="92" />
            <el-table-column prop="observedLocal" label="观测时刻" min-width="126" />
          </el-table>
        </div>
      </el-col>
    </el-row>

    <div class="panel" v-if="selected">
      <div class="panel-head"><span class="panel-title">操作轨迹</span><span class="panel-sub">登记、分摊、暂停/恢复、结束、取消均只追加</span></div>
      <el-timeline>
        <el-timeline-item v-for="l in selected.logs" :key="l.id" :timestamp="`${l.createdLocal} · ${l.operator || '—'}`">
          <b>{{ l.actionName }}</b>
          <span v-if="l.fromStatusName || l.toStatusName">（{{ l.fromStatusName || '—' }} → {{ l.toStatusName || '—' }}）</span>
          <div>{{ l.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <el-dialog v-model="showForm" title="登记电网限功率指令" width="900px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="指令编号"><el-input v-model="form.commandNo" /></el-form-item>
        <el-form-item label="目标上限 kW"><el-input-number v-model="form.targetLimitKw" :min="0" :step="100" /></el-form-item>
        <el-form-item label="分摊方式">
          <el-radio-group v-model="form.allocationMode">
            <el-radio-button label="PROPORTIONAL">按额定容量比例分摊</el-radio-button>
            <el-radio-button label="MANUAL">值班人员逐台确认</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="form.priorityRank" :min="1" :max="999" /><span class="panel-sub">数值越小优先级越高；并行执行仍取最严限值</span></el-form-item>
        <el-form-item label="开始/结束">
          <el-date-picker v-model="form.timeRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束"
            value-format="YYYY-MM-DD HH:mm" :default-time="[new Date(2000,1,1,0,0), new Date(2000,1,1,23,59)]" />
        </el-form-item>
        <el-form-item label="适用机位">
          <el-select v-model="form.turbineIds" multiple filterable style="width: 100%">
            <el-option v-for="t in turbines" :key="t.id" :value="t.id" :label="`${t.unitCode} ${t.unitName}（${t.statusName}，额定 ${t.ratedPowerKw}kW）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="解除条件"><el-input v-model="form.releaseCondition" type="textarea" :rows="2" placeholder="如：调度令 GL-REL-xx 通知解除 / 断面负荷恢复且复核 15 分钟" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="form.owner" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitCommand">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="caseDialog" title="偏差处置 / 复核 / 解除" width="760px">
      <div v-if="currentCase">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="指令">{{ currentCase.commandNo }}</el-descriptions-item>
          <el-descriptions-item label="机位">{{ currentCase.unitCode || '全场/子集' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ currentCase.classificationName }}</el-descriptions-item>
          <el-descriptions-item label="阶段">{{ currentCase.statusName }}</el-descriptions-item>
          <el-descriptions-item label="限值">{{ currentCase.limitKw || currentCase.totalLimitKw }} kW</el-descriptions-item>
          <el-descriptions-item label="实测">{{ currentCase.activePowerKw || currentCase.totalPowerKw }} kW</el-descriptions-item>
        </el-descriptions>
        <el-form :model="caseForm" label-width="100px" size="small">
          <el-form-item label="值班人员"><el-input v-model="caseForm.operator" /></el-form-item>
          <template v-if="currentCase.status === 'OPEN'">
            <el-form-item label="处置动作"><el-input v-model="caseForm.action" type="textarea" :rows="2" placeholder="降负荷设定、远程复核、通知现场等" /></el-form-item>
          </template>
          <template v-if="currentCase.status === 'HANDLED'">
            <el-form-item label="复核结论">
              <el-radio-group v-model="caseForm.result">
                <el-radio label="CONFIRMED">确认偏差</el-radio>
                <el-radio label="DATA_CORRECTED">读数已修正</el-radio>
                <el-radio label="ESCALATE">升级处置</el-radio>
              </el-radio-group>
            </el-form-item>
          </template>
          <template v-if="currentCase.status === 'REVIEWED'">
            <el-form-item label="解除结果">
              <el-radio-group v-model="caseForm.releaseResult">
                <el-radio label="RESOLVED">恢复限值内</el-radio>
                <el-radio label="COMMAND_LIFTED">指令已解除</el-radio>
                <el-radio label="MAINTAINED">维持跟踪</el-radio>
              </el-radio-group>
            </el-form-item>
          </template>
          <el-form-item label="说明"><el-input v-model="caseForm.note" type="textarea" :rows="2" /></el-form-item>
        </el-form>
        <el-button type="primary" v-if="currentCase.status === 'OPEN'" @click="submitCase('handle')">保存处置</el-button>
        <el-button type="primary" v-if="currentCase.status === 'HANDLED'" @click="submitCase('review')">保存复核</el-button>
        <el-button type="success" v-if="currentCase.status === 'REVIEWED'" @click="submitCase('release')">保存解除</el-button>
      </div>
      <el-divider />
      <el-timeline>
        <el-timeline-item v-for="l in currentCase?.logs || []" :key="l.id" :timestamp="`${l.createdLocal} · ${l.operator || '—'}`">
          <b>{{ l.actionName }}</b>：{{ l.detail }}
        </el-timeline-item>
      </el-timeline>
    </el-dialog>
  </div>
</template>
