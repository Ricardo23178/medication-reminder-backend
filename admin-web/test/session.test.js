import test from 'node:test'
import assert from 'node:assert/strict'
import { AdminClient, StaleRequestError, ViewGuard } from '../src/api.js'

const response = (data, status = 200, code = status === 200 ? 200 : status) => ({
  status, ok: status >= 200 && status < 300,
  json: async () => ({ code, message: status === 401 ? '会话过期' : '请求失败', data }),
})
const deferred = () => {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}
const storage = () => {
  const entries = new Map()
  return { getItem: key => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, value), removeItem: key => entries.delete(key) }
}

test('旧会话迟到的401不能清除新管理员会话', async () => {
  const old = deferred()
  let expired = 0
  const client = new AdminClient({ storage: storage(), onUnauthorized: () => expired++, fetchImpl: async url => {
    if (url.endsWith('/login')) return response({ token: 'new-token', user: { role: 'ADMIN' } })
    if (url.endsWith('/me')) return response({ id: 2, account: 'admin', role: 'ADMIN' })
    return old.promise
  } })
  const request = client.request('/users').catch(error => error)
  await client.signIn('admin', 'secret')
  old.resolve(response(null, 401))
  assert.ok(await request instanceof StaleRequestError)
  assert.equal(client.user.id, 2)
  assert.equal(client.token, 'new-token')
  assert.equal(expired, 0)
})

test('登录返回ADMIN仍须经过me验证，普通用户不得进入或留下凭证', async () => {
  const local = storage()
  const client = new AdminClient({ storage: local, fetchImpl: async url => response(url.endsWith('/login')
    ? { token: 'regular-token', user: { role: 'ADMIN' } }
    : { id: 3, role: 'USER' }) })
  await assert.rejects(client.signIn('user', 'secret'), error => error.code === 403)
  assert.equal(client.user, null)
  assert.equal(client.token, '')
  assert.equal(local.getItem('medication.admin.token'), null)
})

test('新登录完成后，旧登录的迟到结果不会覆盖会话', async () => {
  const first = deferred()
  const client = new AdminClient({ storage: storage(), fetchImpl: async (url, options) => {
    if (url.endsWith('/me')) return response({ id: 7, role: 'ADMIN' })
    if (JSON.parse(options.body).account === 'first') return first.promise
    return response({ token: 'second-token' })
  } })
  const previous = client.signIn('first', 'secret').catch(error => error)
  await client.signIn('second', 'secret')
  first.resolve(response({ token: 'first-token' }))
  assert.ok(await previous instanceof StaleRequestError)
  assert.equal(client.token, 'second-token')
  assert.equal(client.user.id, 7)
})

test('当前会话401即使返回非JSON也清除凭证并通知回登录', async () => {
  const local = storage()
  let expired = 0
  let rejectSession = false
  const client = new AdminClient({ storage: local, onUnauthorized: () => expired++, fetchImpl: async url => {
    if (rejectSession) return { status: 401, ok: false, json: async () => { throw new SyntaxError('HTML') } }
    return response(url.endsWith('/login') ? { token: 'token' } : { id: 1, role: 'ADMIN' })
  } })
  await client.signIn('admin', 'secret')
  rejectSession = true
  await assert.rejects(client.request('/admin/users'), error => error.code === 401)
  assert.equal(client.user, null)
  assert.equal(local.getItem('medication.admin.token'), null)
  assert.equal(expired, 1)
})

test('恢复已有凭证同样需要服务器确认管理员角色', async () => {
  const local = storage()
  local.setItem('medication.admin.token', 'saved-user-token')
  let requested
  const client = new AdminClient({ storage: local, fetchImpl: async (url, options) => {
    requested = { url, headers: options.headers }
    return response({ id: 1, role: 'USER' })
  } })
  await assert.rejects(client.restore(), error => error.code === 403)
  assert.equal(requested.url, '/api/admin/me')
  assert.equal(requested.headers.Authorization, 'Bearer saved-user-token')
  assert.equal(client.token, '')
  assert.equal(local.getItem('medication.admin.token'), null)
})

test('切换视图使旧读取失效；保存期间阻止切换与重复保存', async () => {
  const guard = new ViewGuard()
  const oldRead = guard.beginRead()
  assert.equal(guard.navigate(), true)
  assert.equal(oldRead.current(), false)
  const save = deferred()
  const mutation = guard.mutate(() => save.promise)
  assert.equal(guard.navigate(), false)
  await assert.rejects(guard.mutate(() => Promise.resolve()), error => error.code === 'BUSY')
  save.resolve('saved')
  assert.equal(await mutation, 'saved')
  assert.equal(guard.navigate(), true)
})
