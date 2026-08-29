import { describe, it, expect, beforeEach, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken, setUnauthorizedHandler, toApiError } from './http'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken(null)
})

describe('http', () => {
  it('自动附带 Bearer', async () => {
    setToken('tk1')
    let auth = ''
    mock.onGet('/api/ping').reply(config => {
      auth = config.headers?.Authorization as string
      return [200, { ok: true }]
    })
    await http.get('/api/ping')
    expect(auth).toBe('Bearer tk1')
  })

  it('业务错误剥成 ApiError', async () => {
    mock.onPost('/api/x').reply(409, { code: 'CONFLICT', message: '已结束', data: { a: 1 } })
    await expect(http.post('/api/x')).rejects.toMatchObject({
      code: 'CONFLICT', message: '已结束', data: { a: 1 }, status: 409,
    })
  })

  it('401 清 token 并回调（登录接口除外）', async () => {
    setToken('expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mock.onGet('/api/stats').reply(401, { code: 'UNAUTHORIZED', message: '未登录' })
    await expect(http.get('/api/stats')).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(getToken()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalled()
  })

  it('登录接口自身 401 不触发回调', async () => {
    setToken('stale')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    await expect(http.post('/api/auth/login', {})).rejects.toMatchObject({ code: 'AUTH_FAILED' })
    expect(getToken()).toBe('stale')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('toApiError 兜底网络错误', () => {
    expect(toApiError({ response: { status: 500, data: { code: 'INTERNAL', message: 'x' } } }))
      .toMatchObject({ code: 'INTERNAL', status: 500 })
    expect(toApiError({ response: undefined })).toMatchObject({ code: 'NETWORK' })
  })
})
