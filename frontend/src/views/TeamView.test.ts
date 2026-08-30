import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import TeamView from './TeamView.vue'

let mock: MockAdapter

function teamRow(overrides: Record<string, unknown> = {}) {
  return { id: 1, name: '组1', status: 'PENDING', size: 2, overLimit: false,
    leaderName: '张三', rejectReason: null, submittedAt: '2026-08-29T10:00:00', ...overrides }
}

function person(overrides: Record<string, unknown> = {}) {
  return { id: 1, employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机',
    teamId: null, teamName: null, leaderName: null, isLeader: false, participated: false, ...overrides }
}

function teamDetail(overrides: Record<string, unknown> = {}) {
  return { id: 1, name: '组1', status: 'PENDING', rejectReason: null, capToken: 'cap-1',
    overLimit: false, submittedAt: 'x',
    members: [{ employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机', isLeader: true }],
    ...overrides }
}

async function mountView() {
  const wrapper = mount(TeamView, { global: { plugins: [createPinia(), ElementPlus] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  mock.onGet('/api/teams').reply(200, { total: 1, records: [teamRow()] })
})

describe('TeamView', () => {
  it('列表渲染含组长列与 DRAFT 状态', async () => {
    mock.onGet('/api/teams').reply(200, { total: 2, records: [
      teamRow(),
      teamRow({ id: 2, name: '组2', status: 'DRAFT', leaderName: '李四', submittedAt: null }),
    ] })
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('组长')
    expect(text).toContain('张三')
    expect(text).toContain('李四')
    expect(text).toContain('草稿')
    expect(text).toContain('待审核')
  })

  it('状态筛选支持草稿', async () => {
    let params: Record<string, unknown> = {}
    mock.onGet('/api/teams').reply(config => {
      params = config.params as Record<string, unknown>
      return [200, { total: 0, records: [] }]
    })
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as { statusFilter: string; load: () => Promise<void> }
    vm.statusFilter = 'DRAFT'
    await vm.load()
    expect(params).toMatchObject({ status: 'DRAFT' })
  })

  it('驳回不填理由不能提交', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    ;(wrapper.vm as unknown as { rejectTarget: { id: number; name: string } }).rejectTarget = { id: 1, name: '组1' }
    ;(wrapper.vm as unknown as { rejectReason: string }).rejectReason = '  '
    await (wrapper.vm as unknown as { submitReject: () => Promise<void> }).submitReject()
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
    await (wrapper.vm as unknown as { pass: (t: { id: number; name: string }) => Promise<void> })
      .pass({ id: 1, name: '组1' })
    await flushPromises()
    expect(body).toEqual({ action: 'PASS' })
  })

  it('删除组需确认并调用 DELETE', async () => {
    mock.onGet('/api/teams').reply(200, { total: 1, records: [teamRow()] })
    mock.onDelete('/api/teams/1').reply(200, { ok: true })
    const spy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()
    await (wrapper.vm as unknown as { removeTeam: (t: { id: number; name: string }) => Promise<void> })
      .removeTeam(teamRow())
    await flushPromises()
    expect(spy.mock.calls[0]![0]).toContain('组员将回到未报名状态')
    expect(mock.history.delete.filter(h => h.url === '/api/teams/1')).toHaveLength(1)
  })

  it('详情抽屉成员表含员工编号列与组长标记', async () => {
    mock.onGet('/api/teams').reply(200, { total: 1, records: [teamRow()] })
    mock.onGet('/api/teams/1').reply(200, teamDetail())
    mock.onGet('/api/teams/1/events').reply(200, [
      { id: 1, type: 'CREATED', detail: null, createdAt: '2026-08-29T10:00:00' },
      { id: 2, type: 'SUBMITTED', detail: '提交 1 人', createdAt: '2026-08-29T10:01:00' },
    ])
    const wrapper = await mountView()
    await (wrapper.vm as unknown as { openDetail: (t: { id: number }) => Promise<void> }).openDetail({ id: 1 })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('员工编号')
    expect(text).toContain('E001')
    expect(text).toContain('组长')
    expect(text).toContain('提交 1 人')
    expect(text).toContain('创建组')
  })

  it('管理员改组提交员工编号列表', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/1/members').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, teamDetail()]
    })
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as { openMemberEditor: (id: number) => void; picked: Array<Record<string, string>>; leaderEmployeeId: string; saveMembers: () => Promise<void> }
    vm.openMemberEditor(1)
    vm.picked = [
      { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' },
      { employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语' },
    ]
    vm.leaderEmployeeId = 'E001'
    await vm.saveMembers()
    expect(body).toEqual({ leaderEmployeeId: 'E001', memberEmployeeIdList: ['E001', 'E002'] })
  })

  it('新建组：搜索花名册点选后 POST /api/teams', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    mock.onGet('/api/roster').reply(200, { total: 2, records: [
      person(),
      person({ id: 2, employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语' }),
    ] })
    let body: Record<string, unknown> | undefined
    mock.onPost('/api/teams').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, teamDetail({ status: 'CONFIRMED' })]
    })
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      memberVisible: boolean
      searchKw: string
      doSearch: () => Promise<void>
      searchResults: Array<Record<string, unknown>>
      pick: (row: Record<string, unknown>) => void
      picked: unknown[]
      leaderEmployeeId: string
      saveMembers: () => Promise<void>
    }
    vm.openCreate()
    expect(vm.memberVisible).toBe(true)
    vm.searchKw = '张'
    await vm.doSearch()
    expect(vm.searchResults).toHaveLength(2)
    vm.pick(vm.searchResults[0]!)
    vm.pick(vm.searchResults[1]!)
    expect(vm.picked).toHaveLength(2)
    expect(vm.leaderEmployeeId).toBe('E001')
    await vm.saveMembers()
    expect(body).toEqual({ leaderEmployeeId: 'E001', memberEmployeeIdList: ['E001', 'E002'] })
  })

  it('人员搜索：结果含所在组，查看组打开详情抽屉', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    mock.onGet('/api/roster').reply(200, { total: 1, records: [
      person({ teamId: 5, teamName: '组3', leaderName: '王五' }),
    ] })
    mock.onGet('/api/teams/5').reply(200, teamDetail({ id: 5, name: '组3' }))
    mock.onGet('/api/teams/5/events').reply(200, [])
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      openPersonSearch: () => void
      personVisible: boolean
      personKw: string
      doPersonSearch: () => Promise<void>
      personResults: Array<Record<string, unknown>>
      viewTeam: (row: Record<string, unknown>) => Promise<void>
      detailVisible: boolean
    }
    vm.openPersonSearch()
    expect(vm.personVisible).toBe(true)
    vm.personKw = '张三'
    await vm.doPersonSearch()
    expect(vm.personResults).toHaveLength(1)
    await vm.viewTeam(vm.personResults[0]!)
    await flushPromises()
    expect(vm.detailVisible).toBe(true)
    expect(wrapper.text()).toContain('组3')
  })
})
