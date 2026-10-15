export class ApiError extends Error {
  constructor(message, code) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export class StaleRequestError extends Error {
  constructor() { super('请求所属会话已结束'); this.name = 'StaleRequestError' }
}

export class AdminClient {
  constructor({ fetchImpl = globalThis.fetch.bind(globalThis), storage = globalThis.sessionStorage, onUnauthorized = () => {}, onForbidden = () => {} } = {}) {
    this.fetchImpl = fetchImpl
    this.storage = storage
    this.onUnauthorized = onUnauthorized
    this.onForbidden = onForbidden
    this.token = ''
    this.user = null
    this.generation = 0
  }

  clear() {
    this.generation++
    this.token = ''
    this.user = null
    this.storage?.removeItem('medication.admin.token')
  }

  async request(path, { method = 'GET', body, auth = true } = {}) {
    const generation = this.generation
    const token = this.token
    const assertCurrent = () => {
      if (generation !== this.generation || token !== this.token) throw new StaleRequestError()
    }
    let response
    let result
    try {
      response = await this.fetchImpl(`/api${path}`, {
        method,
        headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(auth && token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        credentials: 'omit',
      })
      assertCurrent()
      try { result = await response.json() } catch (error) {
        // Expired credentials must still be removed when a proxy returns an HTML error page.
        if (![401, 403].includes(response.status)) throw error
        result = {}
      }
      assertCurrent()
    } catch (error) {
      assertCurrent()
      if (error instanceof StaleRequestError) throw error
      throw new ApiError(response ? '服务器返回格式异常，请稍后重试' : '无法连接服务，请检查后端是否启动', response?.status ?? 'NETWORK')
    }
    const code = Number(result?.code)
    if (response.status === 401 || code === 401) {
      if (auth) { this.clear(); this.onUnauthorized() }
      throw new ApiError(result?.message || '登录已过期，请重新登录', 401)
    }
    if (response.status === 403 || code === 403) {
      if (auth) { this.clear(); this.onForbidden() }
      throw new ApiError(result?.message || '当前账号无后台访问权限', 403)
    }
    if (!response.ok || ![0, 200].includes(code)) throw new ApiError(result?.message || '请求失败，请重试', code || response.status)
    return result.data
  }

  async verify() {
    const generation = this.generation
    const user = await this.request('/admin/me')
    if (generation !== this.generation) throw new StaleRequestError()
    if (user?.role !== 'ADMIN') {
      this.clear()
      this.onForbidden()
      throw new ApiError('当前账号无后台访问权限', 403)
    }
    this.user = user
    this.storage?.setItem('medication.admin.token', this.token)
    return user
  }

  async signIn(account, password) {
    this.clear()
    const generation = this.generation
    const result = await this.request('/auth/login', { method: 'POST', body: { account, password }, auth: false })
    if (generation !== this.generation) throw new StaleRequestError()
    if (!result?.token) throw new ApiError('登录响应缺少凭证', 'INVALID_RESPONSE')
    this.token = result.token
    try { return await this.verify() } catch (error) {
      if (!(error instanceof StaleRequestError)) this.clear()
      throw error
    }
  }

  async restore() {
    const saved = this.storage?.getItem('medication.admin.token')
    if (!saved) return null
    this.clear()
    this.token = saved
    try { return await this.verify() } catch (error) {
      if (!(error instanceof StaleRequestError)) this.clear()
      throw error
    }
  }
}

// A view owns its reads; a pending mutation owns navigation until its refresh completes.
export class ViewGuard {
  constructor() { this.version = 0; this.busy = false }
  beginRead() {
    const version = ++this.version
    return { current: () => version === this.version }
  }
  navigate() {
    if (this.busy) return false
    this.version++
    return true
  }
  invalidate() { this.version++ }
  async mutate(task) {
    if (this.busy) throw new ApiError('操作正在提交，请稍候', 'BUSY')
    this.busy = true
    try { return await task() } finally { this.busy = false }
  }
}
