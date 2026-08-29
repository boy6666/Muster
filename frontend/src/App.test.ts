import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'
import { router } from './router'

describe('App', () => {
  it('渲染路由出口', async () => {
    router.push('/admin/home')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })
    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(true)
    expect(wrapper.html()).toContain('home-view')
  })
})
