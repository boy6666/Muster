import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import App from './App.vue'
import { router } from './router'
import { http } from './api/http'

let mock: MockAdapter

beforeEach(() => {
  // 守卫要求已登录才进 /admin/home
  localStorage.setItem('muster.token', 'test-token')
  // 本机 8080 可能有真实服务在跑，必须拦掉 /admin/home 触发的请求
  mock = new MockAdapter(http)
  mock.onGet('/api/activity').reply(200, null)
  mock.onGet('/api/stats').reply(200,
    { total: 0, registered: 0, notRegistered: 0, teamCount: 0, pendingTeamCount: 0 })
})

describe('App', () => {
  it('渲染路由出口', async () => {
    router.push('/admin/home')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router, createPinia()] } })
    // App 装配了路由出口，且守卫放行已登录用户（本例渲染 AdminLayout 占位节点）
    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'RouterView' }).html()).toContain('div')
  })
})
