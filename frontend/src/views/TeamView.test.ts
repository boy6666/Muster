import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import TeamView from './TeamView.vue'

let mock: MockAdapter
async function mountView() {
  const wrapper = mount(TeamView, { global: { plugins: [createPinia(), ElementPlus] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
})

describe('TeamView', () => {
  it('列表渲染含超员标记与状态', async () => {
    mock.onGet('/api/teams').reply(200, {
      total: 1,
      records: [{ id: 1, name: '组1', status: 'PENDING', size: 7, overLimit: true,
                  rejectReason: null, submittedAt: '2026-08-29T10:00:00' }],
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('组1')
    expect(wrapper.text()).toContain('超员')
    expect(wrapper.text()).toContain('待审核')
  })

  it('驳回不填理由不能提交', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    ;(wrapper.vm as any).rejectTarget = { id: 1, name: '组1' }
    ;(wrapper.vm as any).rejectReason = '  '
    await (wrapper.vm as any).submitReject()
    expect(mock.history.some(h => h.url?.includes('/review'))).toBe(false)
  })

  it('审核通过调用 PUT /review 并刷新', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/1/review').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()
    await (wrapper.vm as any).pass({ id: 1, name: '组1' })
    await flushPromises()
    expect(body).toEqual({ action: 'PASS' })
  })

  it('管理员改组提交成员手机列表', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/3/members').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).editTarget = { id: 3 }
    ;(wrapper.vm as any).picked = [
      { name: '张三', phone: '13800000001', department: '计算机' },
      { name: '李四', phone: '13800000002', department: '外语' },
    ]
    await (wrapper.vm as any).saveMembers()
    expect(body).toEqual({ memberPhoneList: ['13800000001', '13800000002'] })
  })

  it('详情抽屉展示成员与流水', async () => {
    mock.onGet('/api/teams').reply(200, { total: 1, records: [
      { id: 1, name: '组1', status: 'PENDING', size: 2, overLimit: false,
        rejectReason: null, submittedAt: '2026-08-29T10:00:00' },
    ] })
    mock.onGet('/api/teams/1').reply(200, {
      id: 1, name: '组1', status: 'PENDING', rejectReason: null, overLimit: false,
      submittedAt: 'x', members: [{ name: '张三', phone: '13800000001', department: '计算机' }],
    })
    mock.onGet('/api/teams/1/events').reply(200, [
      { id: 1, type: 'SUBMITTED', detail: '提交 1 人', createdAt: '2026-08-29T10:00:00' },
    ])
    const wrapper = await mountView()
    await (wrapper.vm as any).openDetail({ id: 1 })
    await flushPromises()
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('提交 1 人')
  })
})
