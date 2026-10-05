import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call (promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

// 同 call，但把后端 message 一并带出（更换登记需要区分正常 / 工单补齐机位 / 幂等命中）
async function callWithMessage (promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return { data: body.data, message: body.message }
}

// ---------------- 风场 ----------------
const farm = {
  list: () => call(http.get('/farm/list')),
  vo: () => call(http.get('/farm/vo')),
  get: (id) => call(http.get(`/farm/${id}`)),
  create: (data) => call(http.post('/farm', data)),
  update: (id, data) => call(http.put(`/farm/${id}`, data)),
  status: (id, status) => call(http.post(`/farm/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/farm/${id}`))
}

// ---------------- 机位 ----------------
const turbine = {
  list: (farmId) => call(http.get('/turbine/list', { params: { farmId } })),
  grid: (farmId) => call(http.get('/turbine/grid', { params: { farmId } })),
  get: (id) => call(http.get(`/turbine/${id}`)),
  create: (data) => call(http.post('/turbine', data)),
  update: (id, data) => call(http.put(`/turbine/${id}`, data)),
  status: (id, status) => call(http.post(`/turbine/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/turbine/${id}`))
}

// ---------------- 运行日志 ----------------
const runlog = {
  list: (turbineId, farmId) => call(http.get('/runlog/list', { params: { turbineId, farmId } })),
  vo: (turbineId, farmId) => call(http.get('/runlog/vo', { params: { turbineId, farmId } })),
  get: (id) => call(http.get(`/runlog/${id}`)),
  create: (data) => call(http.post('/runlog', data)),
  update: (id, data) => call(http.put(`/runlog/${id}`, data)),
  remove: (id) => call(http.delete(`/runlog/${id}`))
}

// ---------------- 检修工单与作业项 ----------------
const workorder = {
  list: (turbineId, farmId) => call(http.get('/workorder/list', { params: { turbineId, farmId } })),
  vo: (turbineId, farmId) => call(http.get('/workorder/vo', { params: { turbineId, farmId } })),
  kanban: (farmId) => call(http.get('/workorder/kanban', { params: { farmId } })),
  get: (id) => call(http.get(`/workorder/${id}`)),
  items: (id) => call(http.get(`/workorder/${id}/items`)),
  create: (data) => call(http.post('/workorder', data)),
  update: (id, data) => call(http.put(`/workorder/${id}`, data)),
  status: (id, status) => call(http.post(`/workorder/${id}/status?status=${encodeURIComponent(status)}`)),
  accept: (id, data) => call(http.post(`/workorder/${id}/accept`, data)),
  remove: (id) => call(http.delete(`/workorder/${id}`)),
  createItem: (data) => call(http.post('/workorder/item', data)),
  updateItem: (id, data) => call(http.put(`/workorder/item/${id}`, data)),
  removeItem: (id) => call(http.delete(`/workorder/item/${id}`))
}

// ---------------- 备件与更换 ----------------
const part = {
  list: (category) => call(http.get('/part/list', { params: { category } })),
  vo: (category) => call(http.get('/part/vo', { params: { category } })),
  get: (id) => call(http.get(`/part/${id}`)),
  create: (data) => call(http.post('/part', data)),
  update: (id, data) => call(http.put(`/part/${id}`, data)),
  stock: (id, delta) => call(http.post(`/part/${id}/stock?delta=${delta}`)),
  remove: (id) => call(http.delete(`/part/${id}`)),
  replacements: (partId, turbineId) => call(http.get('/part/replacement', { params: { partId, turbineId } })),
  replacementVOs: (partId, turbineId) => call(http.get('/part/replacement/vo', { params: { partId, turbineId } })),
  replace: (data) => call(http.post('/part/replacement', data)),
  replaceWithMessage: (data) => callWithMessage(http.post('/part/replacement', data))
}

// ---------------- 安全隔离票 ----------------
const isolation = {
  templates: (maintType) => call(http.get('/isolation/template/list', { params: { maintType } })),
  createTemplate: (data) => call(http.post('/isolation/template', data)),
  updateTemplate: (id, data) => call(http.put(`/isolation/template/${id}`, data)),
  removeTemplate: (id) => call(http.delete(`/isolation/template/${id}`)),
  list: (turbineId, farmId) => call(http.get('/isolation/ticket/list', { params: { turbineId, farmId } })),
  get: (id) => call(http.get(`/isolation/ticket/${id}`)),
  byOrder: (workOrderId) => call(http.get(`/isolation/ticket/by-order/${workOrderId}`)),
  create: (data) => call(http.post('/isolation/ticket', data)),
  remove: (id) => call(http.delete(`/isolation/ticket/${id}`)),
  startConfirm: (id) => call(http.post(`/isolation/ticket/${id}/start-confirm`)),
  completeIsolation: (id) => call(http.post(`/isolation/ticket/${id}/complete-isolation`)),
  startRestore: (id) => call(http.post(`/isolation/ticket/${id}/start-restore`)),
  completeRestore: (id) => call(http.post(`/isolation/ticket/${id}/complete-restore`)),
  ackRisk: (id, action, operator) => call(http.post(`/isolation/ticket/${id}/template-risk`, null, {
    params: { action, operator }
  })),
  addPoint: (data) => call(http.post('/isolation/point', data)),
  updatePoint: (id, data) => call(http.put(`/isolation/point/${id}`, data)),
  removePoint: (id) => call(http.delete(`/isolation/point/${id}`)),
  isolate: (id, data) => call(http.post(`/isolation/point/${id}/isolate`, data)),
  invalid: (id, operator, reason) => call(http.post(`/isolation/point/${id}/invalid`, null, {
    params: { operator, reason }
  })),
  restore: (id, data) => call(http.post(`/isolation/point/${id}/restore`, data))
}

// ---------------- 电网限功率 ----------------
const grid = {
  commands: (farmId) => call(http.get('/grid/command/vo', { params: { farmId } })),
  command: (id) => call(http.get(`/grid/command/${id}/vo`)),
  create: (data) => call(http.post('/grid/command', data)),
  update: (id, data) => call(http.put(`/grid/command/${id}`, data)),
  remove: (id) => call(http.delete(`/grid/command/${id}`)),
  preview: (id) => call(http.post(`/grid/command/${id}/allocation/preview`)),
  confirm: (id, data) => call(http.post(`/grid/command/${id}/allocation/confirm`, data || {})),
  start: (id, data) => call(http.post(`/grid/command/${id}/start`, data || {})),
  pause: (id, data) => call(http.post(`/grid/command/${id}/pause`, data || {})),
  resume: (id, data) => call(http.post(`/grid/command/${id}/resume`, data || {})),
  finish: (id, data) => call(http.post(`/grid/command/${id}/finish`, data || {})),
  cancel: (id, data) => call(http.post(`/grid/command/${id}/cancel`, data || {})),
  sync: (farmId) => call(http.post(`/grid/farm/${farmId}/sync`)),
  events: (farmId) => call(http.get('/grid/event/vo', { params: { farmId } })),
  cases: (farmId) => call(http.get('/grid/case/vo', { params: { farmId } })),
  case: (id) => call(http.get(`/grid/case/${id}/vo`)),
  handle: (id, data) => call(http.post(`/grid/case/${id}/handle`, data)),
  review: (id, data) => call(http.post(`/grid/case/${id}/review`, data)),
  release: (id, data) => call(http.post(`/grid/case/${id}/release`, data))
}

// ---------------- 发电统计 ----------------
const stats = {
  overview: () => call(http.get('/stats/overview')),
  daily: (farmId) => call(http.get('/stats/daily', { params: { farmId } })),
  monthly: (farmId) => call(http.get('/stats/monthly', { params: { farmId } })),
  byFarm: () => call(http.get('/stats/by-farm')),
  byTurbine: (farmId) => call(http.get('/stats/by-turbine', { params: { farmId } })),
  workOrderStatus: () => call(http.get('/stats/work-order-status')),
  maintType: () => call(http.get('/stats/maint-type')),
  partCategory: () => call(http.get('/stats/part-category')),
  dict: () => call(http.get('/stats/dict'))
}

export default { http, farm, turbine, runlog, workorder, part, stats, isolation, grid }
