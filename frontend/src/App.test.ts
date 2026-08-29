import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'

beforeEach(() => {
  // 守卫要求已登录才进 /admin/home
  localStorage.setItem('muster.token', 'test-token')
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
