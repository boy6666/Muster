import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import { confirm } from '../components/ui/confirm'
import TeamView from './TeamView.vue'

vi.mock('../components/ui/confirm', () => ({ confirm: vi.fn().mockResolvedValue(undefined) }))

let mock: MockAdapter
const wrappers: VueWrapper[] = []

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
  const wrapper = mount(TeamView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

const vmOf = (wrapper: VueWrapper) => wrapper.vm as unknown as Record<string, any>
// Teleport 到 body 的抽屉/弹窗内容不在 wrapper 里,统一从 document.body 断言
const bodyText = () => document.body.textContent ?? ''

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  vi.mocked(confirm).mockClear()
  mock.onGet('/api/teams').reply(200, { total: 1, records: [teamRow()] })
})

afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
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

  it('点「草稿」chip 以 status=DRAFT 重新加载', async () => {
    const wrapper = await mountView()
    const draft = wrapper.findAll('.chip').find(c => c.text().includes('草稿'))!
    await draft.trigger('click')
    await flushPromises()
    const last = mock.history.get.filter(h => (h.url ?? '').split('?')[0] === '/api/teams').at(-1)!
    expect(last.params).toMatchObject({ status: 'DRAFT', page: 1, size: 10 })
  })

  it('chips 渲染各状态计数（挂载时并行请求 size=1 取 total）', async () => {
    const totals: Record<string, number> = { '': 7, PENDING: 3, CONFIRMED: 2, REJECTED: 1, DRAFT: 1 }
    mock.onGet('/api/teams').reply(config => {
      const s = (config.params as Record<string, unknown> | undefined)?.status
      return [200, { total: totals[typeof s === 'string' ? s : ''] ?? 0, records: [] }]
    })
    const wrapper = await mountView()
    const chips = wrapper.findAll('.chip')
    expect(chips).toHaveLength(5)
    const expectChip = (i: number, label: string, count: string) => {
      expect(chips[i]!.text()).toContain(label)
      expect(chips[i]!.text()).toContain(count)
    }
    expectChip(0, '全部状态', '7')
    expectChip(1, '待审核', '3')
    expectChip(2, '已通过', '2')
    expectChip(3, '已驳回', '1')
    expectChip(4, '草稿', '1')
    const countReqs = mock.history.get.filter(h => (h.url ?? '').split('?')[0] === '/api/teams' && h.params?.size === 1)
    expect(countReqs).toHaveLength(5)
  })

  it('驳回不填理由不能提交', async () => {
    const wrapper = await mountView()
    const vm = vmOf(wrapper)
    vm.rejectTarget = { id: 1, name: '组1' }
    vm.rejectReason = '  '
    await vm.submitReject()
    expect(mock.history.some(h => h.url?.includes('/review'))).toBe(false)
  })

  it('审核通过先确认再调用 PUT /review 并刷新', async () => {
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/1/review').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const wrapper = await mountView()
    await vmOf(wrapper).pass({ id: 1, name: '组1' })
    await flushPromises()
    expect(vi.mocked(confirm).mock.calls[0]![0]).toContain('确认通过 组1')
    expect(body).toEqual({ action: 'PASS' })
  })

  it('删除组需确认并调用 DELETE', async () => {
    mock.onDelete('/api/teams/1').reply(200, { ok: true })
    const wrapper = await mountView()
    await vmOf(wrapper).removeTeam(teamRow())
    await flushPromises()
    expect(vi.mocked(confirm).mock.calls[0]![0]).toContain('组员将回到未报名状态')
    expect(mock.history.delete.filter(h => h.url === '/api/teams/1')).toHaveLength(1)
  })

  it('详情抽屉成员表含员工编号列、组长标记与事件时间线', async () => {
    mock.onGet('/api/teams/1').reply(200, teamDetail())
    mock.onGet('/api/teams/1/events').reply(200, [
      { id: 1, type: 'CREATED', detail: null, createdAt: '2026-08-29T10:00:00' },
      { id: 2, type: 'SUBMITTED', detail: '提交 1 人', createdAt: '2026-08-29T10:01:00' },
    ])
    const wrapper = await mountView()
    await vmOf(wrapper).openDetail({ id: 1 })
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('员工编号')
    expect(text).toContain('E001')
    expect(text).toContain('组长')
    expect(text).toContain('提交 1 人')
    expect(text).toContain('创建组')
  })

  it('管理员改组提交员工编号列表', async () => {
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/1/members').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, teamDetail()]
    })
    const wrapper = await mountView()
    const vm = vmOf(wrapper)
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
    const vm = vmOf(wrapper)
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
    mock.onGet('/api/roster').reply(200, { total: 1, records: [
      person({ teamId: 5, teamName: '组3', leaderName: '王五' }),
    ] })
    mock.onGet('/api/teams/5').reply(200, teamDetail({ id: 5, name: '组3' }))
    mock.onGet('/api/teams/5/events').reply(200, [])
    const wrapper = await mountView()
    const vm = vmOf(wrapper)
    vm.openPersonSearch()
    expect(vm.personVisible).toBe(true)
    vm.personKw = '张三'
    await vm.doPersonSearch()
    expect(vm.personResults).toHaveLength(1)
    await vm.viewTeam(vm.personResults[0]!)
    await flushPromises()
    expect(vm.detailVisible).toBe(true)
    expect(bodyText()).toContain('组3')
  })

  it('PENDING 抽屉 footer 渲染通过/驳回按钮且点通过调用 PUT /review', async () => {
    mock.onGet('/api/teams/1').reply(200, teamDetail())
    mock.onGet('/api/teams/1/events').reply(200, [])
    let body: Record<string, unknown> | undefined
    mock.onPut('/api/teams/1/review').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const wrapper = await mountView()
    await vmOf(wrapper).openDetail({ id: 1 })
    await flushPromises()
    const foot = document.querySelector('.dr-foot') as HTMLElement
    expect(foot.textContent).toContain('通过')
    expect(foot.textContent).toContain('驳回')
    expect(foot.textContent).toContain('管理员改组')
    ;(foot.querySelector('.btn.ok') as HTMLElement).click()
    await flushPromises()
    expect(body).toEqual({ action: 'PASS' })
  })

  it('非 PENDING 抽屉 footer 仅渲染管理员改组', async () => {
    mock.onGet('/api/teams/1').reply(200, teamDetail({ status: 'CONFIRMED' }))
    mock.onGet('/api/teams/1/events').reply(200, [])
    const wrapper = await mountView()
    await vmOf(wrapper).openDetail({ id: 1 })
    await flushPromises()
    const foot = document.querySelector('.dr-foot') as HTMLElement
    expect(foot.textContent).toContain('管理员改组')
    expect(foot.textContent).not.toContain('通过')
    expect(foot.textContent).not.toContain('驳回')
  })
})
