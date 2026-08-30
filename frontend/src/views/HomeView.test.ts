import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
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
let wrapper: VueWrapper | null = null

const ACTIVE_ACTIVITY = {
  id: 1, name: '迎新晚会', startTime: '2099-09-03T18:00:00', endTime: '2099-09-03T21:00:00',
  groupSizeLimit: 5, qrToken: 'tok', exported: false, manuallyEnded: false, windowStatus: 'ACTIVE',
}
const STATS = { total: 10, registered: 6, notRegistered: 4, teamCount: 2, pendingTeamCount: 1 }
const DISTRIBUTION = [
  { size: 3, count: 2, overLimit: false },
  { size: 6, count: 1, overLimit: true },
]

function mountHome(): VueWrapper {
  wrapper = mount(HomeView)
  return wrapper
}

function mockActive(): void {
  mock.onGet('/api/activity').reply(200, ACTIVE_ACTIVITY)
  mock.onGet('/api/stats').reply(200, STATS)
  mock.onGet('/api/stats/distribution').reply(200, DISTRIBUTION)
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  setSocketFactory(url => new FakeWebSocket(url) as unknown as WebSocket)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('HomeView', () => {
  it('无活动时显示自绘空态占位', async () => {
    mock.onGet('/api/activity').reply(200, '')
    const w = mountHome()
    await flushPromises()
    expect(w.text()).toContain('没有进行中的活动')
  })

  it('窗口非 ACTIVE 时显示占位', async () => {
    mock.onGet('/api/activity').reply(200, { windowStatus: 'NOT_STARTED' })
    const w = mountHome()
    await flushPromises()
    expect(w.text()).toContain('没有进行中的活动')
  })

  it('ACTIVE 渲染横幅:活动名/进行中/距结束/每组上限', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('迎新晚会')
    expect(text).toContain('进行中')
    expect(text).toContain('每组上限 5 人')
    expect(text).toContain('距结束')
  })

  it('四张统计卡:已报名 6/未报名 4/分组数 2/待审核 1,不含花名册总人数', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('已报名')
    expect(text).toContain('未报名')
    expect(text).toContain('分组数')
    expect(text).toContain('待审核')
    expect(text).not.toContain('花名册总人数')
    expect(text).toContain('6')
    expect(text).toContain('4')
    expect(text).toContain('2')
    expect(text).toContain('1')
  })

  it('事件流渲染 recentEvents(组名/事件文案/detail)', async () => {
    mockActive()
    mock.onGet('/api/stats').reply(200, {
      ...STATS,
      recentEvents: [{ teamId: 1, teamName: '组2', type: 'SUBMITTED', detail: '提交 2 人', createdAt: '2026-08-30T10:00:00' }],
    })
    const w = mountHome()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('组2')
    expect(text).toContain('提交报名')
    expect(text).toContain('提交 2 人')
  })

  it('参加率环 legend 含 60%', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    expect(w.text()).toContain('60%')
  })

  it('组人数分布渲染桶与超限警示', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('3人 · ×2')
    expect(text).toContain('6人 · ×1')
    expect(text).toContain('超出 5 人上限')
  })

  it('WS 推送帧更新数字与事件流', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    FakeWebSocket.instances[0]!.onmessage?.({ data: JSON.stringify({
      total: 12, registered: 9, notRegistered: 3, teamCount: 3, pendingTeamCount: 0,
      recentEvents: [{ teamId: 2, teamName: '组3', type: 'CREATED', detail: '3 人', createdAt: '2026-08-30T10:01:00' }],
    }) })
    await vi.waitFor(() => {
      const text = w.text()
      expect(text).toContain('9')
      expect(text).toContain('组3')
      expect(text).toContain('创建组')
    })
  })

  it('导出两个按钮存在', async () => {
    mockActive()
    const w = mountHome()
    await flushPromises()
    expect(w.find('[data-test="export-joined"]').exists()).toBe(true)
    expect(w.find('[data-test="export-missing"]').exists()).toBe(true)
  })
})
