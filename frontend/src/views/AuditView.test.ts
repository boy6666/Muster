import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import AuditView from './AuditView.vue'

let mock: MockAdapter
async function mountView() {
  const wrapper = mount(AuditView, { global: { plugins: [createPinia(), ElementPlus] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
})

describe('AuditView', () => {
  it('列表渲染操作人、动作与详情', async () => {
    mock.onGet('/api/audit').reply(200, {
      total: 2,
      records: [
        { id: 2, adminUsername: 'admin', action: 'TEAM_REVIEW', detail: '组1 PASS', createdAt: '2026-08-29T10:30:00' },
        { id: 1, adminUsername: 'admin', action: 'ROSTER_IMPORT', detail: '导入 980 人', createdAt: '2026-08-29T09:00:00' },
      ],
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('TEAM_REVIEW')
    expect(wrapper.text()).toContain('组1 PASS')
    expect(wrapper.text()).toContain('导入 980 人')
    expect(wrapper.text()).toContain('2026-08-29 09:00')
  })

  it('按动作筛选时把 action 传给后端', async () => {
    mock.onGet('/api/audit').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    let params: Record<string, unknown> | undefined
    mock.onGet('/api/audit').reply(config => {
      params = config.params as Record<string, unknown>
      return [200, { total: 0, records: [] }]
    })
    ;(wrapper.vm as any).actionFilter = 'ROSTER_IMPORT'
    await (wrapper.vm as any).search()
    await flushPromises()
    expect(params!.action).toBe('ROSTER_IMPORT')
    expect(params!.page).toBe(1)
  })
})
