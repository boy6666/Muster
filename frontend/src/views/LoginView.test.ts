import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken } from '../api/http'
import LoginView from './LoginView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  setActivePinia(createPinia())
  localStorage.clear()
  setToken(null)
  push.mockClear()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, user: string, pass: string) {
  await wrapper.find('input[autocomplete="username"]').setValue(user)
  await wrapper.find('input[type="password"]').setValue(pass)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('LoginView', () => {
  it('登录成功后保存 token 并跳转后台', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't1', username: 'admin' })
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), ElementPlus] } })
    await fillAndSubmit(wrapper, 'admin', 'admin123')
    expect(getToken()).toBe('t1')
    expect(push).toHaveBeenCalledWith('/admin/home')
  })

  it('登录失败展示后端 message 且不跳转', async () => {
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), ElementPlus] } })
    await fillAndSubmit(wrapper, 'admin', 'bad')
    expect(wrapper.text()).toContain('账号或密码错误')
    expect(push).not.toHaveBeenCalled()
  })
})
