<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const CATEGORIES = [
  { value: 'BLADE', label: '叶片' },
  { value: 'GEARBOX', label: '齿轮箱' },
  { value: 'GENERATOR', label: '发电机' },
  { value: 'PITCH', label: '变桨系统' },
  { value: 'ELECTRIC', label: '电气' },
  { value: 'HYDRAULIC', label: '液压' },
  { value: 'FASTENER', label: '紧固件' },
  { value: 'LUBE', label: '润滑油脂' }
]

const parts = ref([])
const turbines = ref([])
const orders = ref([])
const replacements = ref([])
const category = ref('')
const step = ref(1)
const dialog = ref(false)
// 提交在途标记：防止双击 / 网络重试时同一张表单发两笔（与服务端令牌幂等双保险）
const submitting = ref(false)
// 服务端校验失败后需要修正的问题（机位 / 工单 / 余量），展示在表单上方让用户改完继续
const formError = ref('')
// 本次登记的幂等令牌：一次填写生成一个，重试沿用；成功后换新令牌
const replaceForm = ref(emptyReplaceForm())
const form = ref(emptyForm())

function uuid () {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return 'rt-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

function emptyReplaceForm () {
  return { partId: null, turbineId: null, workOrderId: null, clientToken: uuid(), qty: 1, operator: '', note: '' }
}

function emptyForm () {
  return {
    partCode: 'SP-0000',
    partName: '',
    spec: '',
    category: 'ELECTRIC',
    unit: '件',
    stockQty: 10,
    safetyStock: 4,
    location: '1 号库 A 区',
    unitPrice: 1000,
    supplier: '',
    note: ''
  }
}

const lowCount = computed(() => parts.value.filter((p) => p.lowStock).length)
const warnCount = computed(() => replacements.value.filter((r) => r.warningFlag).length)
// 冲突 / 失配的旧登记：必须显式待核对，不静默改写
const pendingCount = computed(() => replacements.value.filter((r) => r.pendingCheck).length)
const pendingRows = computed(() => replacements.value.filter((r) => r.pendingCheck))

// 选中的工单 / 机位，用于前端即时联动（仅为减少误选；最终裁决一律在服务端）
const selectedOrder = computed(() => orders.value.find((o) => o.id === replaceForm.value.workOrderId) || null)
const selectedTurbine = computed(() => turbines.value.find((t) => t.id === replaceForm.value.turbineId) || null)

// 只选工单时，页面明确提示「机位将由工单补齐」为哪个机位，不做隐式填值
const orderTurbineCode = computed(() => selectedOrder.value?.unitCode || '')
const turbineFilledByOrder = computed(() =>
  !!selectedOrder.value && replaceForm.value.turbineId == null)

// 机位与工单是否冲突（同传不一致）：提交前先在页面拦截并指出应改选谁
const associationConflict = computed(() => {
  const o = selectedOrder.value
  const t = selectedTurbine.value
  return !!(o && t && o.turbineId !== t.id)
})

// 选中备件实时库存（失败方刷新后看到真实余量）
const selectedPartStock = computed(() => {
  const p = parts.value.find((x) => x.id === replaceForm.value.partId)
  return p ? { stock: p.stockQty, unit: p.unit, code: p.partCode } : null
})
const overStock = computed(() =>
  selectedPartStock.value && Number(replaceForm.value.qty) > Number(selectedPartStock.value.stock))

// 仅作业中的工单允许登记备件更换
function orderSelectable (o) {
  return o.status === 'WORKING'
}

// 待核对旧记录整行标红，避免淹没在正常履历里
function replacementRowClass ({ row }) {
  return row.pendingCheck ? 'replacement-pending-row' : ''
}

function barMax (p) {
  return Math.max(Number(p.safetyStock || 0) * 2, Number(p.stockQty || 0) * 1.15, 1)
}
function fillPct (p) {
  return Math.min(100, (Number(p.stockQty || 0) / barMax(p)) * 100)
}
function markPct (p) {
  return Math.min(100, (Number(p.safetyStock || 0) / barMax(p)) * 100)
}

async function load () {
  // 重新拉取真实库存 / 工单状态 / 履历，失败方刷新即看到已生效操作与真实剩余量
  const [p, t, o, r] = await Promise.all([
    api.part.vo(category.value || null),
    api.turbine.grid(props.farmId),
    api.workorder.kanban(props.farmId),
    api.part.replacementVOs()
  ])
  parts.value = p
  turbines.value = t
  orders.value = o
  replacements.value = r
}

async function changeStock (p, delta) {
  try {
    await api.part.stock(p.id, delta)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// 选工单：机位保持为空时由后端按工单补齐（页面上即时回显补齐目标），用户也可显式选定机位
function onOrderChange () {
  formError.value = ''
}
function onTurbineChange () {
  formError.value = ''
}

// 一键把机位改选为工单所属机位
function fixTurbineToOrder () {
  if (selectedOrder.value) {
    replaceForm.value.turbineId = selectedOrder.value.turbineId
    formError.value = ''
  }
}
// 一键清空与当前机位冲突的工单，改选该机位下作业中的工单
function clearOrder () {
  replaceForm.value.workOrderId = null
  formError.value = ''
}

async function submitReplace () {
  if (submitting.value) return
  formError.value = ''
  // 前端即时提示（不是唯一闸门，服务端会重新核对）
  if (!replaceForm.value.partId) {
    formError.value = '请先选择要更换的备件'
    return
  }
  if (replaceForm.value.turbineId == null && replaceForm.value.workOrderId == null) {
    formError.value = '请至少选择机位或关联工单（只选工单时机位将由工单自动补齐并回显）'
    return
  }
  if (associationConflict.value) {
    formError.value = `机位与工单不属于同一台机组：工单 ${selectedOrder.value.orderNo} 属于机位 ${selectedOrder.value.unitCode}，当前机位是 ${selectedTurbine.value.unitCode}。请把机位改选为 ${selectedOrder.value.unitCode}，或改选该工单。`
    return
  }
  if (selectedOrder.value && !orderSelectable(selectedOrder.value)) {
    formError.value = `工单 ${selectedOrder.value.orderNo} 当前为「${selectedOrder.value.statusName}」，只有作业中的工单才能登记备件，请改选机位 ${selectedOrder.value.unitCode} 下作业中的工单。`
    return
  }
  if (overStock.value) {
    formError.value = `备件 ${selectedPartStock.value.code} 当前库存仅 ${selectedPartStock.value.stock} ${selectedPartStock.value.unit}，本次数量超出，请刷新后按真实余量登记。`
    return
  }

  const payload = {
    partId: replaceForm.value.partId,
    turbineId: replaceForm.value.turbineId ?? null,
    workOrderId: replaceForm.value.workOrderId ?? null,
    // 幂等令牌随重试沿用；同一次更换只可能落一条履历
    clientToken: replaceForm.value.clientToken,
    qty: Number(replaceForm.value.qty),
    operator: replaceForm.value.operator,
    note: replaceForm.value.note
  }
  submitting.value = true
  try {
    const { message } = await api.part.replaceWithMessage(payload)
    ElMessage.success(message || '更换登记已完成')
    replaceForm.value = emptyReplaceForm()
    await load()
  } catch (e) {
    // 明确指出应修正的机位 / 工单 / 余量，保留表单让用户改完继续
    formError.value = e.message
    // 冲突 / 库存被并发改过时，拉取真实库存与已生效履历
    await load()
  } finally {
    submitting.value = false
  }
}

async function submit () {
  try {
    await api.part.create(form.value)
    ElMessage.success('备件已创建')
    dialog.value = false
    form.value = emptyForm()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePart (p) {
  await ElMessageBox.confirm(`确认删除备件 ${p.partCode}？`, '删除备件', { type: 'warning' })
  try {
    await api.part.remove(p.id)
    ElMessage.success('备件已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
watch(() => props.farmId, load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">备件与更换</span>
      <span class="view-tip">库存条 + 步进器（−/+ 改数量），红标记是安全库存线；低于安全库存仍可出库但会打预警标记</span>
      <el-button size="small" style="margin-left: auto" @click="router.push('/turbine')">← 机位总览</el-button>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">备件 {{ parts.length }} 种</span>
      <span class="chip" :class="{ warn: lowCount > 0 }">低库存 {{ lowCount }}</span>
      <span class="chip" :class="{ bad: warnCount > 0 }">预警更换 {{ warnCount }}</span>
      <span class="chip" :class="{ bad: pendingCount > 0 }">待核对 {{ pendingCount }}</span>
      <el-select v-model="category" size="small" clearable placeholder="全部分类" style="width: 140px" @change="load">
        <el-option v-for="c in CATEGORIES" :key="c.value" :value="c.value" :label="c.label" />
      </el-select>
      <el-select v-model="step" size="small" style="width: 92px">
        <el-option :value="1" label="步长 1" />
        <el-option :value="5" label="步长 5" />
        <el-option :value="10" label="步长 10" />
      </el-select>
      <el-button size="small" type="primary" style="margin-left: auto" @click="dialog = true">新增备件</el-button>
    </div>

    <!-- ---------- 库存条 + 步进器 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">库存（步进器直接增减）</span></div>

      <div v-for="p in parts" :key="p.id" class="part-row" :class="{ low: p.lowStock }">
        <div>
          <div class="part-name">{{ p.partCode }} {{ p.partName }}</div>
          <div class="part-meta">
            {{ p.categoryName }} · {{ p.spec }} · {{ p.location }} · 单价 {{ Number(p.unitPrice).toLocaleString() }} 元
          </div>
        </div>

        <div>
          <div class="stock-bar" :class="{ low: p.lowStock }">
            <i :style="{ width: fillPct(p) + '%' }"></i>
            <span class="sb-mark" :style="{ left: markPct(p) + '%' }"></span>
            <span class="sb-text">库存 {{ p.stockQty }} {{ p.unit }} / 安全 {{ p.safetyStock }}</span>
          </div>
          <el-tag size="small" :type="p.lowStock ? 'warning' : 'success'" style="margin-top: 5px">
            {{ p.lowStock ? '低于安全库存' : '库存充足' }}
          </el-tag>
        </div>

        <div style="display: flex; align-items: center; gap: 8px">
          <div class="stepper">
            <button @click="changeStock(p, -step)">−</button>
            <span class="sv">{{ p.stockQty }}</span>
            <button @click="changeStock(p, step)">+</button>
          </div>
          <el-button link type="danger" size="small" @click="removePart(p)">删除</el-button>
        </div>
      </div>

      <div class="empty" v-if="!parts.length">暂无备件</div>
    </div>

    <!-- ---------- 更换登记 ---------- -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">更换登记</span>
        <span class="panel-sub">
          机位与工单必须属于同一台机组；只选工单时机位按工单自动补齐；仅作业中工单可登记；
          数量不得超过当前库存，出库后低于安全库存会自动打预警
        </span>
      </div>

      <!-- 服务端 / 前端核对不通过：指出应修正的机位或工单，改完继续 -->
      <el-alert
        v-if="formError"
        type="error"
        show-icon
        :closable="false"
        style="margin: 8px 12px"
      >
        <div>{{ formError }}</div>
        <div v-if="associationConflict" style="margin-top: 6px; display: flex; gap: 8px">
          <el-button size="small" type="primary" @click="fixTurbineToOrder">
            把机位改选为 {{ selectedOrder.unitCode }}
          </el-button>
          <el-button size="small" @click="clearOrder">改选该机位下的工单</el-button>
        </div>
      </el-alert>

      <el-form :inline="true" size="small" style="padding: 0 12px">
        <el-form-item label="备件">
          <el-select v-model="replaceForm.partId" style="width: 250px" @change="formError = ''">
            <el-option v-for="p in parts" :key="p.id" :value="p.id"
              :label="`${p.partCode} ${p.partName}（库存 ${p.stockQty}${p.unit}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="机位">
          <el-select v-model="replaceForm.turbineId" clearable placeholder="留空则由工单补齐"
            style="width: 190px" @change="onTurbineChange">
            <el-option v-for="t in turbines" :key="t.id" :value="t.id" :label="`${t.unitCode} ${t.unitName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联工单">
          <el-select v-model="replaceForm.workOrderId" clearable style="width: 260px" @change="onOrderChange">
            <el-option
              v-for="o in orders"
              :key="o.id"
              :value="o.id"
              :disabled="!orderSelectable(o)"
              :label="`${o.orderNo} ${o.title}｜${o.unitCode}｜${o.statusName}${orderSelectable(o) ? '' : '（不可登记）'}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="replaceForm.qty" :min="1" :max="200" />
        </el-form-item>
        <el-form-item label="更换人">
          <el-input v-model="replaceForm.operator" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting"
            :disabled="associationConflict || overStock" @click="submitReplace">
            登记更换
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 实时联动提示（提交前） -->
      <div style="padding: 0 12px 10px; font-size: 12px; line-height: 1.7">
        <el-alert
          v-if="associationConflict"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 6px"
          :title="`机位 ${selectedTurbine.unitCode} 与工单 ${selectedOrder.orderNo}（属于机位 ${selectedOrder.unitCode}）冲突，必须改选一致后才能提交`"
        />
        <el-alert
          v-else-if="turbineFilledByOrder"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 6px"
          :title="`未单独选机位，将按工单补齐为机位 ${orderTurbineCode}；若工单与实际作业机位不符，请改选机位或工单`"
        />
        <el-alert
          v-if="selectedOrder && orderSelectable(selectedOrder)"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 6px"
          :title="`工单 ${selectedOrder.orderNo} 属于机位 ${selectedOrder.unitCode}（作业中，可登记）`"
        />
        <el-alert
          v-if="overStock"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 6px"
          :title="`本次数量 ${replaceForm.qty} 超过当前库存 ${selectedPartStock.stock} ${selectedPartStock.unit}，请刷新后按真实余量登记`"
        />
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">更换记录</span>
        <span class="panel-sub">关联核对按当前机位 / 工单实时判定；冲突旧数据标记「待核对」，不静默改写历史</span>
      </div>

      <el-alert
        v-for="r in pendingRows"
        :key="'pending-' + r.id"
        type="warning"
        show-icon
        :closable="false"
        style="margin: 8px 12px"
        :title="`待核对：${r.partCode}（${r.replaceTime}）— ${r.checkNote}`"
      />

      <el-table :data="replacements" size="small" max-height="300" :row-class-name="replacementRowClass">
        <el-table-column prop="partCode" label="备件编号" width="130" />
        <el-table-column prop="partName" label="名称" min-width="140" />
        <el-table-column prop="unitCode" label="机位" width="80" />
        <el-table-column label="工单" width="170">
          <template #default="{ row }">
            <div>{{ row.orderNo || '—' }}</div>
            <div v-if="row.orderStatusName" style="font-size: 11px; color: #909399">{{ row.orderStatusName }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="70" />
        <el-table-column prop="replaceTime" label="更换时间" width="150" />
        <el-table-column prop="operator" label="更换人" width="120" />
        <el-table-column label="关联核对" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.pendingCheck ? 'danger' : 'success'">
              {{ row.pendingCheck ? '待核对' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="预警" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.warningFlag ? 'danger' : 'success'">
              {{ row.warningFlag ? '预警' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stockAfter" label="剩余" width="70" />
        <el-table-column prop="sourceName" label="来源" width="150" />
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.pendingCheck ? row.checkNote : row.note }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新增备件" width="560px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="备件编号"><el-input v-model="form.partCode" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.partName" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category">
            <el-option v-for="c in CATEGORIES" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" style="width: 100px" /></el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.stockQty" :min="0" /></el-form-item>
        <el-form-item label="安全库存"><el-input-number v-model="form.safetyStock" :min="0" /></el-form-item>
        <el-form-item label="位置"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.unitPrice" :min="0" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.el-table .replacement-pending-row) {
  background-color: #fef0f0;
}
:deep(.el-table .replacement-pending-row:hover > td) {
  background-color: #fde2e2 !important;
}
</style>
