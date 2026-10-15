<script setup>
import { computed, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { AdminClient, StaleRequestError, ViewGuard } from './api.js'
import { validateReason, validateSetting } from './validation.js'

const sections = [
  { key: 'overview', label: '运行概览', eyebrow: 'OVERVIEW', subtitle: '查看用户规模、异常与通知投递情况' },
  { key: 'users', label: '用户管理', eyebrow: 'USERS', subtitle: '查询用户与调整账号启用状态' },
  { key: 'dict', label: '字典配置', eyebrow: 'DICTIONARIES', subtitle: '维护系统预设字典的可用值', category: 'DICT' },
  { key: 'template', label: '通知模板', eyebrow: 'TEMPLATES', subtitle: '维护站内通知标题和正文模板', category: 'TEMPLATE' },
  { key: 'param', label: '系统参数', eyebrow: 'PARAMETERS', subtitle: '调整通知投递开关与每批处理数量', category: 'PARAM' },
  { key: 'events', label: '异常任务', eyebrow: 'EVENTS', subtitle: '查看异常事件流转状态，处理由授权家属完成' },
  { key: 'notifications', label: '通知投递', eyebrow: 'DELIVERY', subtitle: '查看站内通知投递状态与重试次数' },
  { key: 'audit-logs', label: '操作审计', eyebrow: 'AUDIT LOG', subtitle: '追溯成功的用户状态与配置变更' },
]
const defaultEventLabels = { UNCONFIRMED_TIMEOUT: '超时未确认', CONSECUTIVE_UNCONFIRMED: '连续未确认', PLAN_ENDED_UNFINISHED: '计划结束仍有未完成任务' }
const state = reactive({ booting: true, loggingIn: false, user: null, denied: false, authError: '', tab: 'overview', rows: [], overview: null, eventLabels: { ...defaultEventLabels }, loading: false, saving: false, error: '', notice: '', page: 1, pageSize: 20, total: 0, keyword: '', status: '', action: '', edit: null })
const credentials = reactive({ account: '', password: '' })
const form = reactive({ value: '', reason: '', error: '' })
const guard = new ViewGuard()
const client = new AdminClient({ onUnauthorized: () => endSession('登录已过期，请重新登录'), onForbidden: () => endSession('当前账号无后台访问权限', true) })
const current = computed(() => sections.find(section => section.key === state.tab))
const isSetting = computed(() => !!current.value.category)
const locked = computed(() => state.saving)
const stats = computed(() => [
  { label: '用户总数', value: state.overview?.totalUsers, unit: '个账号' },
  { label: '启用用户', value: state.overview?.activeUsers, unit: '个账号' },
  { label: '待处理异常', value: state.overview?.openEvents, unit: '项事件' },
  { label: '待投递通知', value: state.overview?.pendingNotifications, unit: '条通知' },
  { label: '投递失败', value: state.overview?.failedNotifications, unit: '条通知' },
  { label: '提醒任务', value: state.overview?.totalTasks, unit: '项任务' },
])
const statusOptions = computed(() => state.tab === 'users'
  ? [{ value: '1', label: '已启用' }, { value: '0', label: '已禁用' }]
  : state.tab === 'events'
    ? ['OPEN', 'NOTIFIED', 'ACKNOWLEDGED', 'CLOSED'].map(value => ({ value, label: labels[value] }))
    : ['PENDING', 'SENT', 'FAILED', 'CANCELLED'].map(value => ({ value, label: labels[value] })))
const columns = computed(() => {
  if (state.tab === 'users') return [['id', '用户 ID', 90], ['account', '账号', 150], ['nickname', '昵称', 140], ['role', '角色', 110], ['status', '状态', 100], ['createTime', '注册时间', 175], ['lastLoginAt', '最近登录', 175]]
  if (isSetting.value) return [['code', '配置编码', 220], ['name', '名称', 150], ['description', '说明', 220], ['value', '当前值', 260], ['version', '版本', 80], ['updateTime', '更新时间', 175]]
  if (state.tab === 'events') return [['id', '事件 ID', 100], ['type', '异常类型', 170], ['status', '状态', 115], ['occurredAt', '发生时间', 175], ['notifiedAt', '通知时间', 175], ['acknowledgedAt', '知晓时间', 175], ['closedAt', '关闭时间', 175]]
  if (state.tab === 'notifications') return [['id', '通知 ID', 100], ['type', '通知类型', 160], ['bizType', '业务类型', 160], ['sendStatus', '投递状态', 115], ['retryCount', '重试次数', 100], ['createTime', '创建时间', 175], ['sentAt', '投递时间', 175]]
  return [['id', '审计 ID', 95], ['operatorId', '操作人 ID', 110], ['action', '操作', 160], ['targetType', '对象类型', 130], ['targetId', '对象 ID', 100], ['reason', '操作原因', 230], ['createTime', '操作时间', 175]]
})
const labels = { ADMIN: '管理员', USER: '普通用户', OPEN: '待处理', NOTIFIED: '已通知', ACKNOWLEDGED: '已知晓', CLOSED: '已关闭', PENDING: '待投递', SENT: '已投递', FAILED: '失败', CANCELLED: '已撤销', USER_STATUS: '调整用户状态', SETTING_UPDATE: '修改系统配置' }
function display(row, field) {
  const value = row[field]
  if (value === null || value === undefined || value === '') return '—'
  if (field === 'status' && state.tab === 'users') return Number(value) === 1 ? '已启用' : '已禁用'
  if (field.endsWith('Time') || field.endsWith('At')) return String(value).replace('T', ' ').replace(/\.\d+(?:Z)?$/, '')
  if (field === 'type' && state.tab === 'events') return state.eventLabels[value] || value
  if (state.tab === 'notifications' && (field === 'type' || field === 'bizType')) {
    return { SYSTEM: '系统通知', FAMILY_ALERT: '家属异常通知', ABNORMAL_EVENT: '用药异常' }[value] || value
  }
  if (['role', 'status', 'sendStatus', 'action'].includes(field)) return labels[value] || value
  return value
}
function statusType(row, field) {
  const value = row[field]
  if (value === 'FAILED' || value === 0) return 'danger'
  if (['PENDING', 'OPEN'].includes(value)) return 'warning'
  if (['CANCELLED', 'CLOSED'].includes(value)) return 'info'
  return 'success'
}
function endSession(message = '', denied = false) {
  client.clear()
  guard.invalidate()
  state.user = null
  state.denied = denied
  state.authError = message
  state.rows = []
  state.overview = null
  state.eventLabels = { ...defaultEventLabels }
  state.total = 0
  state.tab = 'overview'
  state.page = 1
  state.keyword = state.status = state.action = ''
  state.error = state.notice = ''
  resetEditor()
  state.loading = false
  credentials.account = ''
  credentials.password = ''
}
async function login() {
  if (state.loggingIn || !credentials.account.trim() || !credentials.password) return
  state.loggingIn = true
  state.authError = ''
  state.denied = false
  try {
    state.user = await client.signIn(credentials.account.trim(), credentials.password)
    load()
  } catch (error) {
    if (!(error instanceof StaleRequestError)) { state.authError = error.message; state.denied = error.code === 403 }
  } finally { state.loggingIn = false; credentials.password = '' }
}
async function load() {
  if (!state.user) return
  const ticket = guard.beginRead()
  const generation = client.generation
  const active = () => ticket.current() && generation === client.generation && !!state.user
  state.loading = true
  state.error = ''
  state.rows = []
  state.overview = null
  state.total = 0
  let path
  if (state.tab === 'overview') path = '/admin/overview'
  else if (isSetting.value) path = `/admin/settings?category=${current.value.category}`
  else {
    const query = new URLSearchParams({ page: String(state.page), pageSize: String(state.pageSize) })
    if (state.tab === 'users' && state.keyword.trim()) query.set('keyword', state.keyword.trim())
    if (state.status !== '') query.set('status', state.status)
    if (state.tab === 'audit-logs' && state.action) query.set('action', state.action)
    path = `/admin/${state.tab}?${query}`
  }
  try {
    const [data, dictionaries] = await Promise.all([client.request(path), state.tab === 'events' ? client.request('/admin/settings?category=DICT') : Promise.resolve(null)])
    if (!active()) return
    if (dictionaries) {
      const dictionary = dictionaries.find(item => item.code === 'event.labels')
      if (dictionary && !validateSetting(dictionary, dictionary.value)) state.eventLabels = { ...defaultEventLabels, ...JSON.parse(dictionary.value) }
    }
    if (state.tab === 'overview') state.overview = data
    else if (isSetting.value) state.rows = Array.isArray(data) ? data : []
    else {
      state.rows = data.items
      state.total = data.total
      state.page = data.page
      state.pageSize = data.pageSize
    }
  } catch (error) { if (active() && !(error instanceof StaleRequestError)) state.error = error.message }
  finally { if (active()) state.loading = false }
}
function navigate(tab) {
  if (state.tab === tab || !guard.navigate()) return
  state.tab = tab
  state.page = 1
  state.keyword = state.status = state.action = ''
  state.notice = ''
  closeEditor()
  load()
}
function search() { if (!locked.value) { state.page = 1; load() } }
function changePage(page) { if (!locked.value) { state.page = page; load() } }
function changeSize(size) { if (!locked.value) { state.pageSize = size; state.page = 1; load() } }
function openEditor(row) {
  if (locked.value) return
  state.edit = { ...row, setting: isSetting.value }
  form.value = String(row.value ?? '')
  form.reason = form.error = ''
}
function resetEditor() { state.edit = null; form.value = form.reason = form.error = '' }
function closeEditor() { if (!locked.value) resetEditor() }
function beforeClose(done) { if (!locked.value) { closeEditor(); done() } }
async function save() {
  if (!state.edit || locked.value) return
  const editing = { ...state.edit }
  form.error = validateReason(form.reason) || (editing.setting ? validateSetting(editing, form.value) : '')
  if (form.error) return
  state.saving = true
  const value = form.value
  const reason = form.reason.trim()
  try {
    await guard.mutate(async () => {
      try {
        await client.request(editing.setting ? `/admin/settings/${editing.id}` : `/admin/users/${editing.id}/status`, {
          method: editing.setting ? 'PUT' : 'PATCH',
          body: editing.setting ? { value, version: editing.version, reason } : { status: Number(editing.status) === 1 ? 0 : 1, reason },
        })
        resetEditor()
        state.notice = '操作已保存，并记录到操作审计。'
        ElMessage.success('保存成功')
        await load()
      } catch (error) {
        if (error instanceof StaleRequestError) return
        if (error.code === 409 && editing.setting && state.user) {
          resetEditor()
          state.notice = '配置已被其他管理员修改。已重新加载最新版本，请重新编辑并确认。'
          ElMessage.warning(state.notice)
          await load()
        } else if (state.edit) form.error = error.message
      }
    })
  } finally { state.saving = false }
}
onMounted(async () => {
  try { state.user = await client.restore(); if (state.user) await load() }
  catch (error) { if (!(error instanceof StaleRequestError)) { state.authError = error.message; state.denied = error.code === 403 } }
  finally { state.booting = false }
})
</script>

<template>
  <div v-if="state.booting" class="boot-screen" v-loading="true" element-loading-text="正在验证管理员身份…" aria-busy="true"></div>
  <main v-else-if="!state.user" class="login-page">
    <section class="login-intro">
      <span class="brand-mark">药</span>
      <p class="eyebrow">MEDICATION COLLABORATION</p>
      <h1>用药协同<br>管理后台</h1>
      <p>维护系统运行配置，关注异常与通知，<br>让每一次协同都有据可循。</p>
      <span class="login-note">仅限已授权的管理员账号</span>
    </section>
    <section class="login-card">
      <p class="eyebrow">ADMIN ACCESS</p>
      <h2>{{ state.denied ? '访问受限' : '管理员登录' }}</h2>
      <p class="muted">使用管理员账号继续</p>
      <el-alert v-if="state.authError" :title="state.authError" :type="state.denied ? 'warning' : 'error'" :closable="false" show-icon class="form-alert" />
      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="账号">
          <el-input v-model="credentials.account" autocomplete="username" maxlength="100" placeholder="请输入管理员账号" :disabled="state.loggingIn" aria-label="管理员账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="credentials.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" :disabled="state.loggingIn" aria-label="密码" />
        </el-form-item>
        <el-button type="primary" native-type="submit" class="login-submit" :loading="state.loggingIn" :disabled="!credentials.account.trim() || !credentials.password">登录后台</el-button>
      </el-form>
      <p class="login-footnote">系统将验证账号的后台访问权限</p>
    </section>
  </main>
  <div v-else class="admin-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark small">药</span><div><strong>用药协同</strong><span>系统管理平台</span></div></div>
      <p class="nav-caption">工作空间</p>
      <nav aria-label="后台导航">
        <button v-for="(section, index) in sections" :key="section.key" :class="['nav-item', { active: state.tab === section.key }]" :aria-current="state.tab === section.key ? 'page' : undefined" :disabled="locked" @click="navigate(section.key)"><span class="nav-number">{{ String(index + 1).padStart(2, '0') }}</span>{{ section.label }}</button>
      </nav>
      <div class="sidebar-note"><span class="status-dot"></span>站内通知管理<span>异常处理由授权家属完成</span></div>
    </aside>
    <div class="workspace">
      <header class="topbar"><span>用药提醒与家属协同系统</span><div><span class="account-name">{{ state.user.nickname || state.user.account }}</span><el-tag size="small" effect="plain">管理员</el-tag><el-button text :disabled="locked" @click="endSession()">退出登录</el-button></div></header>
      <main class="main-content">
        <div class="page-heading"><div><p class="eyebrow">{{ current.eyebrow }}</p><h1>{{ current.label }}</h1><p class="muted">{{ current.subtitle }}</p></div><el-button :loading="state.loading" :disabled="locked" @click="load">刷新数据</el-button></div>
        <el-alert v-if="state.error" :title="state.error" type="error" show-icon :closable="false" class="page-alert" />
        <el-alert v-if="state.notice" :title="state.notice" type="info" show-icon closable class="page-alert" @close="state.notice = ''" />
        <template v-if="state.tab === 'overview'">
          <section class="stat-grid" v-loading="state.loading" aria-label="运行指标">
            <article v-for="(stat, index) in stats" :key="stat.label" :class="['stat-card', { accent: index === 2 || index === 4 }]"><span>{{ stat.label }}</span><strong>{{ stat.value === undefined || stat.value === null ? '—' : stat.value.toLocaleString('zh-CN') }}</strong><small>{{ stat.unit }}</small></article>
          </section>
          <section class="info-panel"><div><p class="eyebrow">MANAGEMENT SCOPE</p><h2>系统运行，清晰可追溯</h2><p>在这里维护用户状态与预设配置，查看异常事件及通知投递状态。所有成功的敏感操作均记录操作人、变更前后值与原因。</p></div><el-button type="primary" plain :disabled="locked" @click="navigate('audit-logs')">查看操作审计</el-button></section>
          <p class="data-note">通知统计反映站内消息投递，不代表短信或厂商推送已送达设备。</p>
        </template>
        <section v-else class="table-panel">
          <div class="table-toolbar">
            <el-form v-if="!isSetting" inline @submit.prevent="search">
              <el-form-item v-if="state.tab === 'users'" label="用户"><el-input v-model="state.keyword" placeholder="搜索账号或昵称" clearable maxlength="100" :disabled="locked" /></el-form-item>
              <el-form-item v-if="state.tab !== 'audit-logs'" label="状态"><el-select v-model="state.status" placeholder="全部状态" clearable :disabled="locked" class="filter-select" @change="search"><el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
              <el-form-item v-if="state.tab === 'audit-logs'" label="操作"><el-select v-model="state.action" placeholder="全部操作" clearable :disabled="locked" class="filter-select" @change="search"><el-option label="调整用户状态" value="USER_STATUS" /><el-option label="修改系统配置" value="SETTING_UPDATE" /></el-select></el-form-item>
              <el-form-item><el-button type="primary" native-type="submit" :disabled="locked || state.loading">查询</el-button></el-form-item>
            </el-form>
            <span v-else class="muted">预设配置 · 仅可编辑值，提交时须填写原因</span>
            <span class="row-count">{{ isSetting ? state.rows.length : state.total }} 条记录</span>
          </div>
          <el-table :data="state.rows" v-loading="state.loading" row-key="id" style="width: 100%" :aria-label="current.label">
            <el-table-column v-if="state.tab === 'audit-logs'" type="expand"><template #default="{ row }"><div class="audit-detail"><div><strong>变更前</strong><pre>{{ row.beforeValue ?? '—' }}</pre></div><div><strong>变更后</strong><pre>{{ row.afterValue ?? '—' }}</pre></div></div></template></el-table-column>
            <el-table-column v-for="[field, label, width] in columns" :key="field" :prop="field" :label="label" :min-width="width" show-overflow-tooltip><template #default="{ row }"><el-tag v-if="['status', 'sendStatus'].includes(field)" :type="statusType(row, field)" size="small" effect="light">{{ display(row, field) }}</el-tag><span v-else>{{ display(row, field) }}</span></template></el-table-column>
            <el-table-column v-if="isSetting || state.tab === 'users'" label="操作" fixed="right" :width="120"><template #default="{ row }"><el-button v-if="isSetting" link type="primary" :disabled="locked || state.loading" @click="openEditor(row)">编辑配置</el-button><el-button v-else link :type="Number(row.status) === 1 ? 'danger' : 'primary'" :disabled="locked || state.loading || row.role === 'ADMIN'" @click="openEditor(row)">{{ row.role === 'ADMIN' ? '管理员保护' : Number(row.status) === 1 ? '禁用账号' : '启用账号' }}</el-button></template></el-table-column>
            <template #empty><el-empty v-if="!state.loading" :description="state.error ? '数据加载失败，请点击刷新重试' : '暂无符合条件的记录'" :image-size="72" /><span v-else>正在加载…</span></template>
          </el-table>
          <div v-if="!isSetting" class="pagination"><el-pagination :current-page="state.page" :page-size="state.pageSize" :page-sizes="[10, 20, 50]" :total="state.total" layout="total, sizes, prev, pager, next" :pager-count="5" :disabled="locked || state.loading" @current-change="changePage" @size-change="changeSize" /></div>
          <p v-if="state.tab === 'audit-logs'" class="table-note">展开记录查看变更前后值。仅记录成功的敏感操作。</p>
        </section>
      </main>
      <footer>用药协同管理平台 <span>·</span> 管理操作请遵循最小必要原则</footer>
    </div>
    <el-dialog :model-value="!!state.edit" :title="state.edit?.setting ? '编辑系统配置' : Number(state.edit?.status) === 1 ? '确认禁用账号' : '确认启用账号'" width="620px" :before-close="beforeClose" :close-on-click-modal="false" :close-on-press-escape="!locked" :show-close="!locked" @update:model-value="value => { if (!value) closeEditor() }">
      <template v-if="state.edit">
        <p class="editor-target">{{ state.edit.setting ? state.edit.name : state.edit.account }} <span>{{ state.edit.setting ? state.edit.code : `用户 ID ${state.edit.id}` }}</span></p>
        <el-alert v-if="!state.edit.setting" :title="Number(state.edit.status) === 1 ? '禁用后该用户将无法继续使用系统。请确认账号与原因。' : '启用后该用户可重新登录并使用系统。'" type="warning" :closable="false" show-icon class="form-alert" />
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item v-if="state.edit.setting" label="配置值">
            <el-input v-model="form.value" type="textarea" :rows="7" :disabled="locked" aria-label="配置值" class="code-input" />
            <p class="field-hint" v-if="state.edit.category === 'DICT'">填写 JSON 对象，保留 UNCONFIRMED_TIMEOUT、CONSECUTIVE_UNCONFIRMED、PLAN_ENDED_UNFINISHED 三个固定键；每个中文展示名为 1～40 字。</p>
            <p class="field-hint" v-else-if="state.edit.category === 'TEMPLATE'">填写 JSON 对象，title 最多 80 字，content 最多 500 字，均不得留空。保存后用于新入队通知；正文按纯文本处理。</p>
            <p class="field-hint" v-else-if="state.edit.code === 'delivery.paused'">true 暂停站内通知投递；false 恢复投递。</p>
            <p class="field-hint" v-else>填写 1～100 的整数，控制每批通知处理数量。</p>
          </el-form-item>
          <el-form-item label="操作原因（必填）"><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请填写 3～200 字的具体原因" :disabled="locked" aria-label="操作原因" /></el-form-item>
          <el-alert v-if="form.error" :title="form.error" type="error" :closable="false" show-icon />
        </el-form>
      </template>
      <template #footer><el-button :disabled="locked" @click="closeEditor">取消</el-button><el-button type="primary" :loading="locked" @click="save">确认保存</el-button></template>
    </el-dialog>
  </div>
</template>
