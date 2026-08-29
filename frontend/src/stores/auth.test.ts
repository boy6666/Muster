import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken } from '../api/http'
import { useAuthStore } from './auth'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  setActivePinia(createPinia())
  localStorage.clear()
  setToken(null)
})

describe('auth store', () => {
  it('登录成功保存 token', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't1', username: 'admin' })
    const store = useAuthStore()
    await store.login('admin', 'admin123')
    expect(store.token).toBe('t1')
    expect(getToken()).toBe('t1')
  })

  it('登录失败抛 ApiError 且不保存', async () => {
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    const store = useAuthStore()
    await expect(store.login('admin', 'bad')).rejects.toMatchObject({ code: 'AUTH_FAILED' })
    expect(store.token).toBeNull()
  })

  it('退出清空 token', () => {
    setToken('t1')
    const store = useAuthStore()
    store.token = 't1'
    store.logout()
    expect(store.token).toBeNull()
    expect(getToken()).toBeNull()
  })

  it('修改密码调用 PUT', async () => {
    let body: unknown
    mock.onPut('/api/auth/password').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const store = useAuthStore()
    await store.changePassword('old1', 'newpass1')
    expect(body).toEqual({ oldPassword: 'old1', newPassword: 'newpass1' })
  })
})
