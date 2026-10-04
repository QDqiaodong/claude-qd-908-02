<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

// 隔离票闭环面板：工单页与机位页共用。
// props.order  关联工单（WorkOrderVO），用于创建票、验收联动
// props.workOrderId 仅有 id 时也可（机位页 orders 已带全量 VO）
// 刷新由父组件统一做（refresh emit），组件内部动作成功后通知父组件重拉
const props = defineProps({
  workOrderId: { type: Number, default: null },
  order: { type: Object, default: null },
  embedded: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

const ticket = ref(null)
const loading = ref(false)
const creating = ref(false)
const ownerInput = ref('')
const showLogs = ref(false)
const showTemplates = ref(false)
const templates = ref([])

const newPoint = ref({ pointName: '', pointType: 'LOCK_OUT', standard: '' })
const personDialog = ref(false)
const personForm = ref({ title: '', tip: '', action: null, point: null, operator: '', verifier: '' })

const acceptDialog = ref(false)
const acceptForm = ref({ result: 'PASS', person: '', note: '' })

const tid = computed(() => props.workOrderId || props.order?.id)
const statusColor = (s) => ({
  DRAFT: '#8b95a5', CONFIRMING: '#f08c00', ISOLATED: '#e2483d',
  RESTORING: '#2f80c2', RESTORED: '#2e9e5b'
}[s] || '#8b95a5')
const typeColor = (t) => ({
  SHUTDOWN: '#2f80c2', POWER_OFF: '#7a5cc4', VOLTAGE_TEST: '#f08c00',
  LOCK_OUT: '#e2483d', MECH_LOCK: '#00838f'
}[t] || '#8b95a5')

async function load () {
  if (!tid.value) { ticket.value = null; return }
  loading.value = true
  try {
    ticket.value = await api.isolation.byOrder(tid.value)
  } finally {
    loading.value = false
  }
}

function changed () { emit('changed') }

async function createTicket () {
  if (!ownerInput.value.trim()) { ElMessage.warning('请填写隔离责任人'); return }
  creating.value = true
  try {
    ticket.value = await api.isolation.create({ workOrderId: tid.value, owner: ownerInput.value.trim() })
    ElMessage.success('隔离票已创建，隔离点已按检修类型从模板带入')
    ownerInput.value = ''
    changed()
  } catch (e) { ElMessage.error(e.message) } finally { creating.value = false }
}

async function removeTicket () {
  await ElMessageBox.confirm(`确认删除草稿隔离票 ${ticket.value.ticketNo}？开始确认后将不能删除。`, '删除隔离票', { type: 'warning' })
  try {
    await api.isolation.remove(ticket.value.id)
    ElMessage.success('草稿票已删除')
    ticket.value = null
    changed()
  } catch (e) { ElMessage.error(e.message) }
}

async function act (promiseFactory, okText) {
  try {
    ticket.value = await promiseFactory()
    ElMessage.success(okText)
    changed()
  } catch (e) { ElMessage.error(e.message); await load(); changed() }
}

const startConfirm = () => act(() => api.isolation.startConfirm(ticket.value.id), '已开始隔离确认，机位转入检修')
const completeIsolation = () => act(() => api.isolation.completeIsolation(ticket.value.id), '隔离已生效，工单可进入作业中')
const startRestore = () => act(() => api.isolation.startRestore(ticket.value.id), '已开始按逆序复电')
const completeRestore = () => act(() => api.isolation.completeRestore(ticket.value.id), '隔离已全部恢复，机位可人工恢复并网')

async function ackRisk (action) {
  const who = ticket.value.owner || ''
  try {
    if (action === 'IGNORE') {
      await ElMessageBox.confirm(
        '留痕忽略表示责任人确认这些新增风险点本次不适用，将写入变更记录；模板若再新增点仍会重新提示。确认？',
        '模板新增风险处理', { type: 'warning', confirmButtonText: '留痕忽略', cancelButtonText: '取消' })
    }
    ticket.value = await api.isolation.ackRisk(ticket.value.id, action, who)
    ElMessage.success(action === 'IMPORT' ? '新增风险点已补入本票，请逐项双人确认' : '差异已留痕')
    changed()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

function openIsolate (point) {
  personForm.value = { title: `隔离确认 · ${point.pointSeq}. ${point.pointName}`, tip: '停机 / 验电 / 上锁须双人，操作人与监护人不能为同一人', action: 'isolate', point, operator: ticket.value.owner || '', verifier: '' }
  personDialog.value = true
}
function openRestore (point) {
  personForm.value = { title: `复电解除 · ${point.pointSeq}. ${point.pointName}`, tip: '复电严格按隔离顺序逆序，当前只能解除高亮的这一项', action: 'restore', point, operator: ticket.value.owner || '', verifier: '' }
  personDialog.value = true
}

async function submitPerson () {
  const f = personForm.value
  if (!f.operator.trim()) { ElMessage.warning('请填写确认人'); return }
  if (f.action === 'isolate' && !f.verifier.trim()) { ElMessage.warning('隔离确认必须填写监护人'); return }
  if (f.action === 'isolate' && f.operator.trim() === f.verifier.trim()) {
    ElMessage.error('操作人与监护人不能为同一人'); return
  }
  try {
    if (f.action === 'isolate') {
      ticket.value = await api.isolation.isolate(f.point.id, { operator: f.operator.trim(), verifier: f.verifier.trim() })
      ElMessage.success('隔离点已双人确认')
    } else {
      ticket.value = await api.isolation.restore(f.point.id, { operator: f.operator.trim() })
      ElMessage.success('已按逆序解除')
    }
    personDialog.value = false
    changed()
  } catch (e) { ElMessage.error(e.message); await load(); changed() }
}

async function markInvalid (point) {
  try {
    const { value } = await ElMessageBox.prompt('请填写失效原因（如挂锁脱落、验电异常）', '标记隔离失效', {
      confirmButtonText: '标记失效并退回确认', cancelButtonText: '取消', inputPlaceholder: '复查发现的异常', type: 'warning'
    })
    ticket.value = await api.isolation.invalid(point.id, ticket.value.owner, value || '复查异常')
    ElMessage.warning('已标记失效，现场作业暂停，须重新双人确认')
    changed()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

async function addPoint () {
  if (!newPoint.value.pointName.trim()) { ElMessage.warning('请填写隔离点名称'); return }
  try {
    ticket.value = await api.isolation.addPoint({ ticketId: ticket.value.id, ...newPoint.value })
    ElMessage.success('已补充隔离点')
    newPoint.value = { pointName: '', pointType: 'LOCK_OUT', standard: '' }
    changed()
  } catch (e) { ElMessage.error(e.message) }
}

// ---------------- 验收 ----------------
function openAccept (result) {
  acceptForm.value = { result, person: '', note: '' }
  acceptDialog.value = true
}
async function submitAccept () {
  if (!acceptForm.value.person.trim()) { ElMessage.warning('请填写验收人'); return }
  try {
    await api.workorder.accept(tid.value, { ...acceptForm.value })
    ElMessage.success(acceptForm.value.result === 'PASS' ? '验收合格，可开始复电' : '已驳回，工单退回作业中')
    acceptDialog.value = false
    changed()
  } catch (e) { ElMessage.error(e.message) }
}

// ---------------- 模板管理 ----------------
async function openTemplates () {
  showTemplates.value = true
  await loadTemplates()
}
async function loadTemplates () {
  templates.value = await api.isolation.templates()
}
async function toggleTpl (row) {
  try {
    await api.isolation.updateTemplate(row.id, { enabled: !row.enabled })
    ElMessage.success(row.enabled ? '模板点已停用（不影响旧票）' : '模板点已启用（仅影响之后新建的票）')
    await loadTemplates()
  } catch (e) { ElMessage.error(e.message) }
}

onMounted(load)
watch(tid, load)
defineExpose({ load })
</script>

<template>
  <div class="iso-panel" v-loading="loading">
    <!-- 无票 -->
    <div v-if="!ticket" class="iso-empty">
      <div class="iso-empty-tip">
        该工单还没有安全隔离票。开始作业前必须完成停机、停电、验电、挂牌上锁 / 机械锁定等隔离确认；
        隔离点会按检修类型「{{ order?.maintTypeName || '对应类型' }}」自动从模板带入，也可针对本次工单补充。
      </div>
      <el-input v-model="ownerInput" size="small" placeholder="隔离责任人（工作负责人）" style="max-width: 260px" />
      <el-button size="small" type="warning" :loading="creating" @click="createTicket">创建安全隔离票</el-button>
      <el-button size="small" text @click="openTemplates">隔离点模板维护</el-button>
    </div>

    <template v-else>
      <!-- 票头 -->
      <div class="iso-head">
        <span class="iso-no">{{ ticket.ticketNo }}</span>
        <el-tag size="small" effect="dark" :style="{ background: statusColor(ticket.status), borderColor: statusColor(ticket.status) }">
          {{ ticket.statusName }}
        </el-tag>
        <span class="chip">责任人 <b>{{ ticket.owner || '—' }}</b></span>
        <span class="chip">隔离点 {{ ticket.isolatedCount }}/{{ ticket.pointTotal }}</span>
        <span class="chip" v-if="ticket.status === 'RESTORING' || ticket.status === 'RESTORED'">
          已复电 {{ ticket.restoredCount }}/{{ ticket.pointTotal }}
        </span>
        <el-button size="small" text @click="showLogs = !showLogs">
          {{ showLogs ? '收起变更记录' : '完整变更记录' }}
        </el-button>
        <el-button size="small" text @click="openTemplates">模板</el-button>
        <el-button v-if="ticket.status === 'DRAFT'" size="small" type="danger" plain @click="removeTicket">删除草稿</el-button>
      </div>

      <div class="iso-time">
        <span v-if="ticket.isolatedLocal">隔离生效 {{ ticket.isolatedLocal }}（{{ ticket.isolatedTz }}）</span>
        <span v-if="ticket.restoredLocal">恢复完成 {{ ticket.restoredLocal }}（{{ ticket.restoredTz }}）</span>
        <span v-if="ticket.invalidCount > 0" class="iso-warn">⚠ {{ ticket.invalidCount }} 项隔离失效，须重新确认</span>
      </div>

      <!-- 验收区：待验收工单 -->
      <div v-if="order?.status === 'CHECKING'" class="iso-accept">
        <template v-if="order.acceptResult === 'PASS'">
          <el-tag type="success" size="small">验收合格 · {{ order.acceptPerson }} · {{ order.acceptLocal }}</el-tag>
          <span class="panel-sub">隔离票可开始复电</span>
        </template>
        <template v-else-if="order.acceptResult === 'FAIL'">
          <el-tag type="danger" size="small">验收驳回，工单已退回作业中</el-tag>
          <el-button size="small" @click="openAccept('PASS')">重新验收</el-button>
        </template>
        <template v-else>
          <span class="panel-title" style="font-size: 13px">作业已提交验收</span>
          <el-button size="small" type="success" @click="openAccept('PASS')">验收合格（允许复电）</el-button>
          <el-button size="small" type="danger" plain @click="openAccept('FAIL')">验收驳回（退回作业）</el-button>
        </template>
      </div>

      <!-- 模板新增风险提示 -->
      <el-alert
        v-if="ticket.newTemplatePoints && ticket.newTemplatePoints.length"
        type="error" :closable="false" show-icon style="margin: 6px 0"
        :title="`模板在建票后新增了 ${ticket.newTemplatePoints.length} 个风险点，必须处理后才能隔离（新增风险不会被旧快照掩盖）`"
      >
        <div v-for="t in ticket.newTemplatePoints" :key="t.id" class="tpl-risk-row">
          <el-tag size="small" :style="{ background: typeColor(t.pointType), borderColor: typeColor(t.pointType) }">{{ t.pointTypeName }}</el-tag>
          <b>{{ t.pointSeq }}. {{ t.pointName }}</b>
          <span>{{ t.standard }}</span>
        </div>
        <div style="margin-top: 4px">
          <el-button size="small" type="primary" @click="ackRisk('IMPORT')">全部补入本票（追加并重新确认）</el-button>
          <el-button size="small" @click="ackRisk('IGNORE')" :disabled="['ISOLATED','RESTORING','RESTORED'].includes(ticket.status)">
            责任人留痕忽略
          </el-button>
        </div>
      </el-alert>

      <!-- 隔离点列表 -->
      <div class="iso-points">
        <div
          v-for="p in ticket.points" :key="p.id"
          class="iso-point"
          :class="{
            done: p.isolated && p.validFlag,
            invalid: !p.validFlag,
            restored: p.restored,
            nextrestore: p.canRestoreNow
          }"
        >
          <div class="ip-main">
            <span class="ip-seq">{{ p.pointSeq }}</span>
            <el-tag size="small" :style="{ background: typeColor(p.pointType), borderColor: typeColor(p.pointType) }">
              {{ p.pointTypeName }}
            </el-tag>
            <div class="ip-name">
              {{ p.pointName }}
              <el-tag v-if="p.source === 'MANUAL'" size="small" type="warning" effect="plain">本次补充</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">模板 v{{ p.snapshotVersion }}</el-tag>
              <el-tag v-if="p.templateChanged" size="small" type="warning">模板已更新至 v{{ p.templateLatestVersion }}（仅提示，不改已确认内容）</el-tag>
              <div class="ip-std">{{ p.standard }}</div>
              <div class="ip-who" v-if="p.isolated">
                操作 {{ p.isolateOperator }} / 监护 {{ p.isolateVerifier }} · {{ p.isolateLocal }}
              </div>
              <div class="ip-who" v-if="p.restored">复电 {{ p.restoreOperator }} · {{ p.restoreLocal }}</div>
            </div>
          </div>
          <div class="ip-actions">
            <template v-if="ticket.status === 'CONFIRMING'">
              <el-button v-if="!p.isolated || !p.validFlag" size="small" type="warning" @click="openIsolate(p)">
                {{ !p.validFlag ? '失效重确（双人）' : '双人确认' }}
              </el-button>
              <el-tag v-else size="small" type="success">已隔离</el-tag>
              <el-button v-if="p.isolated && p.validFlag" size="small" text type="danger" @click="markInvalid(p)">复查失效</el-button>
            </template>
            <template v-else-if="ticket.status === 'RESTORING'">
              <el-button v-if="!p.restored" size="small" type="primary" :disabled="!p.canRestoreNow" @click="openRestore(p)">
                {{ p.canRestoreNow ? '逆序解除' : '等待前序项' }}
              </el-button>
              <el-tag v-else size="small" type="success">已复电</el-tag>
            </template>
            <el-tag v-else-if="ticket.status === 'ISOLATED'" size="small" type="danger">隔离中</el-tag>
            <el-tag v-else-if="ticket.status === 'RESTORED'" size="small" type="success">已恢复</el-tag>
          </div>
        </div>
      </div>

      <!-- 草稿/确认中可补充隔离点 -->
      <div v-if="['DRAFT', 'CONFIRMING'].includes(ticket.status)" class="iso-add">
        <el-input v-model="newPoint.pointName" size="small" placeholder="针对本次工单补充隔离点，如：变桨电池柜放电" style="width: 280px" />
        <el-select v-model="newPoint.pointType" size="small" style="width: 110px">
          <el-option value="SHUTDOWN" label="停机" />
          <el-option value="POWER_OFF" label="停电" />
          <el-option value="VOLTAGE_TEST" label="验电" />
          <el-option value="LOCK_OUT" label="挂牌上锁" />
          <el-option value="MECH_LOCK" label="机械锁定" />
        </el-select>
        <el-input v-model="newPoint.standard" size="small" placeholder="隔离标准（可选）" style="flex: 1" />
        <el-button size="small" @click="addPoint">补充</el-button>
      </div>

      <!-- 阻塞原因 / 待办 -->
      <el-alert
        v-if="ticket.blockers && ticket.blockers.length"
        :type="ticket.status === 'RESTORED' ? 'success' : 'info'"
        :closable="false" show-icon style="margin-top: 6px" title="当前待办与放行条件"
      >
        <div v-for="(b, i) in ticket.blockers" :key="i" class="iso-blocker">• {{ b }}</div>
      </el-alert>

      <!-- 阶段动作 -->
      <div class="iso-actions">
        <el-button v-if="ticket.status === 'DRAFT'" size="small" type="warning" @click="startConfirm">
          开始隔离确认（机位转检修）
        </el-button>
        <el-button v-if="ticket.status === 'CONFIRMING'" size="small" type="error" @click="completeIsolation">
          全部确认完成 · 隔离生效
        </el-button>
        <el-button v-if="ticket.status === 'ISOLATED'" size="small" type="primary"
          :disabled="order?.acceptResult !== 'PASS'" @click="startRestore">
          {{ order?.acceptResult === 'PASS' ? '验收合格，开始逆序复电' : '等待工单验收合格' }}
        </el-button>
        <el-button v-if="ticket.status === 'RESTORING'" size="small" type="success" @click="completeRestore">
          全部逆序解除 · 完成恢复
        </el-button>
        <el-tag v-if="ticket.status === 'RESTORED'" type="success" size="small">
          已恢复：闭环工单后机位方可人工并网（系统不会自动并网）
        </el-tag>
      </div>

      <!-- 完整变更记录 -->
      <div v-if="showLogs" class="iso-logs">
        <div class="panel-title" style="font-size: 13px; margin-bottom: 4px">完整变更记录（只增不改，可追溯）</div>
        <el-timeline>
          <el-timeline-item v-for="log in ticket.logs" :key="log.id"
            :timestamp="`${log.createdLocal}（${log.createdTz}）`" placement="top" size="normal">
            <el-tag size="small" effect="plain">{{ log.actionName }}</el-tag>
            <b v-if="log.fromStatusName || log.toStatusName">
              {{ log.fromStatusName }} → {{ log.toStatusName }}
            </b>
            <span>{{ log.detail }}</span>
            <span class="log-op" v-if="log.operator">— {{ log.operator }}</span>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>

    <!-- 双人确认 / 复电弹窗 -->
    <el-dialog v-model="personDialog" :title="personForm.title" width="440px" append-to-body>
      <div class="panel-sub" style="margin-bottom: 10px">{{ personForm.tip }}</div>
      <el-form label-width="80px" size="small">
        <el-form-item label="确认人">
          <el-input v-model="personForm.operator" :placeholder="personForm.action === 'isolate' ? '操作人' : '复电确认人'" />
        </el-form-item>
        <el-form-item label="监护人" v-if="personForm.action === 'isolate'">
          <el-input v-model="personForm.verifier" placeholder="监护人（须与操作人不同）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="personDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPerson">确认提交</el-button>
      </template>
    </el-dialog>

    <!-- 验收弹窗 -->
    <el-dialog v-model="acceptDialog" :title="acceptForm.result === 'PASS' ? '验收合格登记' : '验收驳回登记'" width="440px" append-to-body>
      <el-form label-width="80px" size="small">
        <el-form-item label="验收人"><el-input v-model="acceptForm.person" /></el-form-item>
        <el-form-item label="验收意见"><el-input v-model="acceptForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptDialog = false">取消</el-button>
        <el-button :type="acceptForm.result === 'PASS' ? 'success' : 'danger'" @click="submitAccept">提交验收</el-button>
      </template>
    </el-dialog>

    <!-- 模板维护弹窗 -->
    <el-dialog v-model="showTemplates" title="安全隔离点模板（按检修类型）" width="760px" append-to-body>
      <div class="panel-sub" style="margin-bottom: 8px">
        模板调整只影响<strong>之后新建</strong>的票；已创建的票持有建票时的不可变快照。
        模板新增点会作为「待处理风险」显式提示到已建未隔离的票，修改安全内容会升级版本号，不会覆盖历史确认。
      </div>
      <el-table :data="templates" size="small" max-height="420">
        <el-table-column prop="maintTypeName" label="检修类型" width="90" />
        <el-table-column prop="pointSeq" label="序" width="44" />
        <el-table-column label="手段" width="86">
          <template #default="{ row }">
            <el-tag size="small" :style="{ background: typeColor(row.pointType), borderColor: typeColor(row.pointType) }">
              {{ row.pointTypeName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pointName" label="隔离点" min-width="150" />
        <el-table-column prop="standard" label="标准" min-width="160" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="50" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch :model-value="!!row.enabled" @change="toggleTpl(row)" active-text="启用" inactive-text="停用" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.iso-panel { border-top: 1px dashed #d7e3e2; padding-top: 8px; margin-top: 8px }
.iso-empty { display: flex; gap: 8px; align-items: center; flex-wrap: wrap }
.iso-empty-tip { width: 100%; color: #6b7785; font-size: 12px; line-height: 1.6 }
.iso-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.iso-no { font-weight: 700; color: #00695c }
.iso-time { display: flex; gap: 12px; font-size: 12px; color: #6b7785; margin: 4px 0 }
.iso-warn { color: #e2483d; font-weight: 600 }
.iso-accept { display: flex; align-items: center; gap: 8px; margin: 6px 0; padding: 6px 8px; background: #f4faf7; border-radius: 6px }
.tpl-risk-row { display: flex; gap: 8px; align-items: center; font-size: 12px; margin: 2px 0 }
.iso-points { display: flex; flex-direction: column; gap: 6px; margin-top: 6px }
.iso-point { display: flex; justify-content: space-between; align-items: center; gap: 8px;
  border: 1px solid #e3eaea; border-radius: 8px; padding: 6px 8px; background: #fff }
.iso-point.done { background: #f6fbf9; border-color: #b9dfd2 }
.iso-point.restored { opacity: .75 }
.iso-point.invalid { border-color: #e2483d; background: #fdf3f2 }
.iso-point.nextrestore { border-color: #2f80c2; border-width: 2px; box-shadow: 0 0 0 2px rgba(47,128,194,.12) }
.ip-main { display: flex; align-items: flex-start; gap: 8px }
.ip-seq { width: 20px; height: 20px; border-radius: 50%; background: #00838f; color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px; flex: none }
.iso-point.restored .ip-seq { background: #2e9e5b }
.ip-name { font-size: 13px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap }
.ip-std { width: 100%; color: #8b95a5; font-size: 12px }
.ip-who { width: 100%; color: #6b7785; font-size: 11px }
.ip-actions { flex: none; display: flex; gap: 4px; align-items: center }
.iso-add { display: flex; gap: 6px; margin-top: 8px }
.iso-blocker { font-size: 12px; line-height: 1.7 }
.iso-actions { margin-top: 8px; display: flex; gap: 8px; align-items: center }
.iso-logs { margin-top: 10px; max-height: 280px; overflow: auto; background: #fafbfc; border-radius: 8px; padding: 8px }
.log-op { color: #8b95a5 }
</style>
