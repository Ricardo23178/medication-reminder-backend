// Runs the real Vue UI in local Chrome with synthetic HTTP fixtures; never writes business data.
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, sep, basename } from 'node:path'
import assert from 'node:assert/strict'

const base = process.env.ADMIN_URL || 'http://127.0.0.1:5173/admin/'
const profile = await mkdtemp(join(tmpdir(), 'medication-admin-browser-'))
const chrome = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
  { windowsHide: true, stdio: 'ignore' })
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
let socket
try {
  let port
  for (let i = 0; i < 100; i++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break } catch { await delay(100) }
  }
  assert.ok(port, 'Chrome debugging endpoint must start')
  const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  socket = new WebSocket(tabs.find(tab => tab.type === 'page').webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
  let id = 0
  const pending = new Map()
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const current = ++id
    const timeout = setTimeout(() => { pending.delete(current); reject(new Error(`${method} timed out`)) }, 10000)
    pending.set(current, { resolve: value => { clearTimeout(timeout); resolve(value) }, reject })
    socket.send(JSON.stringify({ id: current, method, params }))
  })
  const errors = []
  let status = 1, version = 0, batch = '100', conflict = false, revoke = false, loginRole = 'ADMIN'
  const audits = []
  const labels = { UNCONFIRMED_TIMEOUT: '浏览器字典名称', CONSECUTIVE_UNCONFIRMED: '连续未确认', PLAN_ENDED_UNFINISHED: '计划结束未完成' }
  const setting = () => ({ id: 1, category: 'PARAM', code: 'delivery.batch-size', name: '每批消息投递数量', value: batch, description: '1至100的整数', version })
  const page = items => ({ items, total: items.length, page: 1, pageSize: 20 })
  async function respond(event) {
    const path = new URL(event.request.url).pathname
    let code = 200, data
    if (path.endsWith('/auth/login')) data = { token: 'synthetic-browser-only', user: { role: loginRole } }
    else if (revoke) code = 401
    else if (path.endsWith('/admin/me')) { if (loginRole !== 'ADMIN') code = 403; else data = { id: 9001, role: 'ADMIN', nickname: '临时浏览器验收' } }
    else if (path.endsWith('/overview')) data = { totalUsers: 12, activeUsers: 10, openEvents: 3, pendingNotifications: 2, failedNotifications: 1, totalTasks: 120 }
    else if (path.endsWith('/users')) data = page([{ id: 9002, account: 'qa_browser_user', nickname: '验收普通用户', role: 'USER', status }])
    else if (path.endsWith('/status')) { status = JSON.parse(event.request.postData).status; audits.push({ id: 1, action: 'USER_STATUS', operatorId: 9001, targetType: 'USER', targetId: '9002', reason: '浏览器验收调整状态', beforeValue: '1', afterValue: String(status) }) }
    else if (path.endsWith('/settings') && event.request.url.includes('DICT')) data = [{ id: 2, code: 'event.labels', category: 'DICT', value: JSON.stringify(labels), name: '异常类型名称', version: 0 }]
    else if (path.endsWith('/settings')) data = [setting()]
    else if (path.endsWith('/settings/1')) {
      if (conflict) { code = 409; conflict = false; version++ }
      else { batch = JSON.parse(event.request.postData).value; version++ }
    }
    else if (path.endsWith('/events')) data = page([{ id: 5, type: 'UNCONFIRMED_TIMEOUT', status: 'NOTIFIED', occurredAt: '2026-10-15T12:00:00' }])
    else if (path.endsWith('/notifications')) data = page([{ id: 8, type: 'FAMILY_ALERT', bizType: 'ABNORMAL_EVENT', sendStatus: 'FAILED', retryCount: 3 }])
    else if (path.endsWith('/audit-logs')) data = page(audits)
    else throw new Error(`Unexpected API: ${path}`)
    await call('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: code === 401 || code === 403 ? code : 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json;charset=utf-8' }],
      body: Buffer.from(JSON.stringify({ code, message: code === 409 ? '配置版本已变化，请刷新' : code === 401 ? '登录已过期' : code === 403 ? '仅平台管理员可访问' : 'ok', data: data ?? null })).toString('base64') })
  }
  socket.onmessage = ({ data }) => {
    const message = JSON.parse(data)
    if (message.id) {
      const task = pending.get(message.id)
      if (task) { pending.delete(message.id); if (message.error) task.reject(new Error(message.error.message)); else task.resolve(message.result) }
    } else if (message.method === 'Fetch.requestPaused') respond(message.params).catch(error => errors.push(error.message))
    else if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text)
  }
  const evaluate = async expression => {
    const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
    return result.result.value
  }
  const until = async expression => { for (let i = 0; i < 100; i++) { if (await evaluate(expression)) return; await delay(100) } throw new Error(`UI assertion timed out: ${expression}`) }
  const text = phrase => `document.body.innerText.includes(${JSON.stringify(phrase)})`
  const click = phrase => evaluate(`(() => { const button=[...document.querySelectorAll('button')].find(e=>e.innerText.trim()===${JSON.stringify(phrase)}); if(!button || button.disabled) throw new Error('button unavailable'); button.click() })()`)
  const input = (selector, value) => evaluate(`(() => {const el=document.querySelector(${JSON.stringify(selector)}); if(!el) throw new Error('input missing'); el.value=${JSON.stringify(value)}; el.dispatchEvent(new Event('input',{bubbles:true}))})()`)
  const nav = async label => {
    await until(`(() => {const el=[...document.querySelectorAll('.nav-item')].find(e=>e.innerText.includes(${JSON.stringify(label)})); return !!el && !el.disabled})()`)
    await evaluate(`(() => {const el=[...document.querySelectorAll('.nav-item')].find(e=>e.innerText.includes(${JSON.stringify(label)})); el.click()})()`)
  }
  await call('Runtime.enable')
  await call('Page.enable')
  await call('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', requestStage: 'Request' }] })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: base })
  await until(text('管理员登录'))
  await input('input[autocomplete="username"]', 'synthetic_admin')
  await input('input[autocomplete="current-password"]', 'synthetic-not-a-real-password')
  await click('登录后台')
  await until(text('120'))
  await nav('用户管理'); await until(text('qa_browser_user')); await click('禁用账号')
  await until(text('确认禁用账号'))
  await click('确认保存'); await until(text('操作原因须'))
  await input('textarea[aria-label="操作原因"]', '浏览器验收调整状态'); await click('确认保存')
  await until(text('启用账号')); assert.equal(status, 0)
  await nav('操作审计'); await until(text('浏览器验收调整状态'))
  await nav('系统参数'); await until(text('每批消息投递数量')); await click('编辑配置')
  await input('textarea[aria-label="配置值"]', '25'); await input('textarea[aria-label="操作原因"]', '浏览器验收参数变更'); await click('确认保存')
  await until('!document.querySelector(".el-overlay-dialog") || document.querySelector(".el-overlay").style.display === "none"')
  assert.equal(batch, '25')
  await click('编辑配置'); await input('textarea[aria-label="操作原因"]', '浏览器验收版本冲突'); conflict = true; await click('确认保存')
  await until(text('重新')); assert.equal(batch, '25')
  await nav('异常任务'); await until(text('浏览器字典名称'))
  await nav('通知投递'); await until(text('家属异常通知')); await until(text('失败'))
  await delay(3200)
  if (process.env.QA_SCREENSHOT) {
    const shot = await call('Page.captureScreenshot', { format: 'png' })
    await writeFile(process.env.QA_SCREENSHOT, Buffer.from(shot.data, 'base64'))
  }
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await delay(300)
  assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'), 'mobile page must not overflow horizontally')
  if (process.env.QA_MOBILE_SCREENSHOT) {
    const shot = await call('Page.captureScreenshot', { format: 'png' })
    await writeFile(process.env.QA_MOBILE_SCREENSHOT, Buffer.from(shot.data, 'base64'))
  }
  revoke = true; await click('刷新数据'); await until(text('管理员登录'))
  assert.ok(await evaluate('sessionStorage.getItem("medication.admin.token") === null'))
  revoke = false; loginRole = 'USER'
  await input('input[autocomplete="username"]', 'synthetic_user'); await input('input[autocomplete="current-password"]', 'synthetic-password'); await click('登录后台')
  await until(text('访问受限'))
  assert.ok(await evaluate('!document.querySelector(".admin-shell")'))
  assert.deepEqual(errors, [])
  console.log('PASS Chrome UI: login, status/reason/audit, settings, conflict, dictionary, delivery, 390px layout, 401 and USER denial')
} catch (error) {
  console.error(error.message)
  throw error
} finally {
  socket?.close()
  const exited = new Promise(resolve => { if (chrome.exitCode !== null) resolve(); else chrome.once('exit', resolve) })
  chrome.kill()
  await exited
  const resolvedProfile = resolve(profile)
  assert.ok(resolvedProfile.startsWith(resolve(tmpdir()) + sep) && basename(resolvedProfile).startsWith('medication-admin-browser-'), 'cleanup must stay in this run\'s temporary profile')
  await rm(resolvedProfile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 })
}
