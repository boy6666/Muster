import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken } from '../api/http'
import { useAuthStore } from '../stores/auth'
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

function mountView(): VueWrapper {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(LoginView, { global: { plugins: [pinia] } })
}

async function fillAndSubmit(wrapper: VueWrapper, user: string, pass: string) {
  const inputs = wrapper.findAll('input.input')
  await inputs[0]!.setValue(user)
  await inputs[1]!.setValue(pass)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('LoginView', () => {
  it('渲染 HUD 登录页:点将台标题、接入控制台按钮与三格特性', () => {
    const wrapper = mountView()
    expect(wrapper.text()).toContain('MUSTER')
    expect(wrapper.text()).toContain('点将台')
    expect(wrapper.text()).toContain('接 入 控 制 台')
    for (const t of ['实时统计', '智能分组', '一键归档']) {
      expect(wrapper.text()).toContain(t)
    }
  })

  it('挂载时不应发起任何统计类查询(登录页不放数字)', () => {
    mountView()
    expect(mock.history.get).toHaveLength(0)
  })

  it('提交调用 store.login 并携带输入,成功保存 token', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't1', username: 'admin' })
    const wrapper = mountView()
    const spy = vi.spyOn(useAuthStore(), 'login')
    await fillAndSubmit(wrapper, 'admin', 'admin123')
    expect(spy).toHaveBeenCalledWith('admin', 'admin123')
    expect(getToken()).toBe('t1')
  })

  it('登录成功跳转 /admin/home', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't2', username: 'admin' })
    const wrapper = mountView()
    await fillAndSubmit(wrapper, 'admin', 'admin123')
    expect(push).toHaveBeenCalledWith('/admin/home')
  })

  it('登录失败展示后端 message 且不跳转', async () => {
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    const wrapper = mountView()
    await fillAndSubmit(wrapper, 'admin', 'bad')
    expect(wrapper.find('.alert.err').text()).toContain('账号或密码错误')
    expect(push).not.toHaveBeenCalled()
  })
})
