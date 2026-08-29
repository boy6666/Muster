import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import ActivityView from './ActivityView.vue'

// jsdom 无 canvas 2d 上下文，桩掉二维码绘制。
// 用 vi.fn(impl) 而非 mockResolvedValue：vitest 3 的 restoreAllMocks 会把
// mockResolvedValue 设的实现重置为 undefined，导致 toCanvas 返回非 Promise。
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

let mock: MockAdapter
const activity = {
  id: 1, name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, qrToken: 'qr123', exported: false, manuallyEnded: false,
  windowStatus: 'ACTIVE',
}

async function mountView() {
  const wrapper = mount(ActivityView, { global: { plugins: [createPinia(), ElementPlus] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  vi.restoreAllMocks()
})

describe('ActivityView', () => {
  it('无活动（200 空体）时显示创建表单', async () => {
    mock.onGet('/api/activity').reply(200, '')
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('创建活动')
  })

  it('有活动时展示信息与二维码地址', async () => {
    mock.onGet('/api/activity').reply(200, activity)
    mock.onGet('/api/activity/form-url').reply(200, { url: 'http://x/form/qr123' })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('迎新晚会')
    expect(wrapper.text()).toContain('http://x/form/qr123')
  })

  it('创建活动提交正确载荷', async () => {
    mock.onGet('/api/activity').reply(200, '')
    let body: Record<string, unknown> | undefined
    mock.onPost('/api/activity').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, activity]
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).form = {
      name: '迎新晚会', startTime: '2026-08-29T10:00:00',
      endTime: '2026-08-29T12:00:00', groupSizeLimit: 5,
    }
    await (wrapper.vm as any).create()
    expect(body).toMatchObject({ name: '迎新晚会', groupSizeLimit: 5 })
  })

  it('手动结束：确认后调用 POST /end 并刷新', async () => {
    mock.onGet('/api/activity').reply(200, activity)
    mock.onGet('/api/activity/form-url').reply(200, { url: 'u' })
    let ended = false
    mock.onPost('/api/activity/end').reply(() => { ended = true; return [200, ''] })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()
    await wrapper.find('[data-test="end-btn"]').trigger('click')
    await flushPromises()
    expect(ended).toBe(true)
    expect((wrapper.vm as any).activity.windowStatus).toBe('ACTIVE')
  })
})
