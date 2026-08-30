import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import AuditView from './AuditView.vue'

let mock: MockAdapter
const calls: Array<Record<string, unknown>> = []

// 后端 AuditController 实际暴露的是 /api/audit/logs（GET），前端必须与其一致
function mockLogs(reply: { total: number; records: unknown[] }) {
  mock.onGet('/api/audit/logs').reply(config => {
    calls.push(config.params as Record<string, unknown>)
    return [200, reply]
  })
}

async function mountView() {
  const wrapper = mount(AuditView, { global: { plugins: [createPinia()] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  calls.length = 0
  localStorage.clear()
  setToken('test-token')
})

describe('AuditView', () => {
  it('chips 渲染「全部操作」与各操作类型,流水行渲染时间/操作人/操作/详情', async () => {
    mockLogs({
      total: 2,
      records: [
        { id: 2, adminUsername: 'admin', action: 'TEAM_REVIEW', detail: '组1 PASS', createdAt: '2026-08-29T10:30:00' },
        { id: 1, adminUsername: 'admin', action: 'ROSTER_IMPORT', detail: '导入 980 人', createdAt: '2026-08-29T09:00:00' },
      ],
    })
    const wrapper = await mountView()

    const chips = wrapper.findAll('.chip')
    expect(chips.length).toBe(11) // 全部操作 + 10 种操作
    expect(chips[0].text()).toBe('全部操作')
    expect(chips[0].classes()).toContain('active')
    for (const label of ['创建活动', '修改活动', '手动结束', '删除活动', '导出归档', '导入花名册', '添加人员', '删除人员', '管理员改组', '组审核']) {
      expect(wrapper.text()).toContain(label)
    }

    const rows = wrapper.findAll('.log-row')
    expect(rows.length).toBe(2)
    expect(rows[0].find('.t').text()).toBe('2026-08-29 10:30')
    expect(rows[0].find('.t').classes()).toContain('mono')
    expect(rows[0].find('.op').text()).toBe('admin')
    expect(rows[0].find('.act .tag').text()).toBe('组审核')
    expect(rows[0].find('.dt').text()).toBe('组1 PASS')
    expect(rows[1].text()).toContain('导入花名册')
    expect(rows[1].text()).toContain('导入 980 人')
    expect(wrapper.text()).toContain('2026-08-29 09:00')
  })

  it('点击「组审核」chip 以 action=TEAM_REVIEW 重新加载并置为 active', async () => {
    mockLogs({ total: 0, records: [] })
    const wrapper = await mountView()

    const reviewChip = wrapper.findAll('.chip').find(c => c.text() === '组审核')!
    await reviewChip.trigger('click')
    await flushPromises()

    const last = calls[calls.length - 1]
    expect(last.action).toBe('TEAM_REVIEW')
    expect(last.page).toBe(1)
    expect(reviewChip.classes()).toContain('active')
    expect(wrapper.findAll('.chip')[0].classes()).not.toContain('active')
  })

  it('UiPagination 翻页触发重新加载(page/size 参数断言)', async () => {
    mockLogs({
      total: 25,
      records: [
        { id: 1, adminUsername: 'admin', action: 'ACTIVITY_CREATE', detail: '迎新晚会', createdAt: '2026-08-29T10:00:00' },
      ],
    })
    const wrapper = await mountView()
    expect(calls[calls.length - 1].page).toBe(1)

    const pg2 = wrapper.findAll('.pg').find(p => p.text() === '2')!
    await pg2.trigger('click')
    await flushPromises()

    const last = calls[calls.length - 1]
    expect(last.page).toBe(2)
    expect(last.size).toBe(10)
  })
})
