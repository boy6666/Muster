import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import { setSocketFactory } from '../composables/useStats'
import HomeView from './HomeView.vue'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor(public url: string) { FakeWebSocket.instances.push(this) }
  close() { this.onclose?.() }
}

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  setSocketFactory(url => new FakeWebSocket(url) as unknown as WebSocket)
})

describe('HomeView', () => {
  it('无活动时显示占位', async () => {
    mock.onGet('/api/activity').reply(200, '')
    const wrapper = mount(HomeView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有进行中的活动')
  })

  it('窗口非 ACTIVE 时显示占位', async () => {
    mock.onGet('/api/activity').reply(200, { windowStatus: 'NOT_STARTED' })
    const wrapper = mount(HomeView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有进行中的活动')
  })

  it('ACTIVE 时展示统计卡片与导出按钮并接收 WS 帧', async () => {
    mock.onGet('/api/activity').reply(200, { windowStatus: 'ACTIVE' })
    mock.onGet('/api/stats').reply(200,
      { total: 10, joined: 6, notJoined: 4, teamCount: 2, pendingTeamCount: 1 })
    const wrapper = mount(HomeView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('10')
    expect(wrapper.text()).toContain('导出已参加')
    expect(wrapper.text()).toContain('导出未参加')
    FakeWebSocket.instances[0]!.onmessage?.({ data: JSON.stringify(
      { total: 12, joined: 9, notJoined: 3, teamCount: 3, pendingTeamCount: 0 }) })
    await vi.waitFor(() => expect(wrapper.text()).toContain('12'))
  })
})
