import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { toasts } from '../components/ui/toast'
import FormView from './FormView.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { token: 'tk' } }) }))

let mock: MockAdapter
let wrapper: VueWrapper | null = null
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

function person(overrides: Record<string, unknown> = {}) {
  return { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机',
    teamId: null, leader: false, ...overrides }
}

function personById(employeeId: string) {
  const i = Number(employeeId.slice(1))
  return { employeeId, name: `成员${employeeId}`, phone: `1380000000${i}`,
    department: '计算机', teamId: null, leader: false }
}

function teamDetail(overrides: Record<string, unknown> = {}) {
  return { id: 7, name: '组1', status: 'DRAFT', rejectReason: null, capToken: 'cap-7',
    overLimit: false, submittedAt: null,
    members: [{ employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机', isLeader: true }],
    ...overrides }
}

async function mountForm() {
  wrapper = mount(FormView, { attachTo: document.body })
  await flushPromises()
  return wrapper!
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  toasts.splice(0)
  mock.onGet('/api/form/tk').reply(200, info)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.restoreAllMocks()
})

describe('FormView', () => {
  it('活动未开始时显示占位提示', async () => {
    mock.onGet('/api/form/tk').reply(200, { ...info, windowStatus: 'NOT_STARTED' })
    const w = await mountForm()
    expect(w.text()).toContain('活动未开始')
  })

  it('员工编号输入 400ms 防抖后才查询', async () => {
    vi.useFakeTimers()
    try {
      const w = await mountForm()
      mock.onGet('/api/form/tk/person').reply(200, person())
      await w.find('input').setValue('E0')
      await vi.advanceTimersByTimeAsync(200)
      expect(mock.history.get.filter(h => h.url?.includes('/person'))).toHaveLength(0)
      await vi.advanceTimersByTimeAsync(300)
      expect(mock.history.get.filter(h => h.url?.includes('/person'))).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('识别后未在组：首行本人标组长 + 保存草稿/提交报名按钮', async () => {
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(200, person())
    await (w.vm as unknown as { identify: (id: string) => Promise<void> }).identify('E001')
    await flushPromises()
    expect(w.text()).toContain('张三')
    expect(w.text()).toContain('组长')
    expect(w.text()).toContain('保存草稿')
    expect(w.text()).toContain('提交报名')
    expect((w.vm as unknown as { members: unknown[] }).members).toHaveLength(1)
  })

  it('保存草稿只建组不提交', async () => {
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(200, person())
    await (w.vm as unknown as { identify: (id: string) => Promise<void> }).identify('E001')
    mock.onPost('/api/form/tk/teams').reply(200, teamDetail())
    await (w.vm as unknown as { onSaveDraft: () => Promise<void> }).onSaveDraft()
    expect(mock.history.post.filter(h => h.url?.includes('/submit'))).toHaveLength(0)
    expect((w.vm as unknown as { team: { status: string } | null }).team?.status).toBe('DRAFT')
    expect(w.text()).toContain('草稿')
    expect(toasts.some(t => t.msg === '已保存草稿')).toBe(true)
    expect(JSON.parse(localStorage.getItem('muster.team.tk')!)).toEqual({ teamId: 7, cap: 'cap-7' })
  })

  it('提交报名弹手机号验证：空号报错，正确后先建组再提交', async () => {
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(200, person())
    await (w.vm as unknown as { identify: (id: string) => Promise<void> }).identify('E001')
    const vm = w.vm as unknown as Record<string, () => unknown> & { phoneDialog: boolean; phoneError: string; dialogPhone: string; team: { status: string } | null }
    await vm.onSubmitClick()
    expect(vm.phoneDialog).toBe(true)
    await flushPromises()
    expect(document.body.textContent).toContain('组长手机验证')
    await vm.confirmPhone()
    expect(vm.phoneError).toContain('11 位')
    expect(mock.history.post).toHaveLength(0)
    vm.dialogPhone = '13800000001'
    mock.onPost('/api/form/tk/teams').reply(200, teamDetail())
    mock.onPost(new RegExp('/api/form/tk/teams/7/submit\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'PENDING' }))
    await vm.confirmPhone()
    const posts = mock.history.post
    expect(posts.some(h => h.url?.endsWith('/teams'))).toBe(true)
    const submitCall = posts.find(h => h.url?.includes('/submit'))
    expect(submitCall).toBeTruthy()
    expect(JSON.parse(submitCall!.data as string)).toEqual({ leaderPhone: '13800000001' })
    expect(vm.team?.status).toBe('PENDING')
    expect(toasts.some(t => t.msg === '已提交，等待审核')).toBe(true)
  })

  it('超上限仅警告不阻断提交', async () => {
    mock.onGet('/api/form/tk').reply(200, { ...info, groupSizeLimit: 1 })
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(config =>
      [200, personById(String(config.params.employeeId))])
    const vm = w.vm as unknown as Record<string, unknown> & { phoneDialog: boolean; onSubmitClick: () => void }
    await (vm.identify as (id: string) => Promise<void>)('E001')
    await (vm.addMember as (id: string) => Promise<boolean>)('E002')
    expect(w.text()).toContain('已超出上限')
    await vm.onSubmitClick()
    await flushPromises()
    expect(vm.phoneDialog).toBe(true)
  })

  it('少于上限时灰色提示', async () => {
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(200, person())
    await (w.vm as unknown as { identify: (id: string) => Promise<void> }).identify('E001')
    await flushPromises()
    expect(w.text()).toContain('少于上限 5 人')
  })

  it('PENDING 组显示审核中标签且无操作按钮', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'PENDING', submittedAt: '2026-08-29T10:00:00' }))
    const w = await mountForm()
    expect(w.text()).toContain('审核中')
    expect(w.text()).toContain('审核中，不能修改或删除')
    expect(w.text()).not.toContain('修改组员')
    expect(w.text()).not.toContain('删除本组')
  })

  it('CONFIRMED 组只读展示', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'CONFIRMED', submittedAt: '2026-08-29T10:00:00' }))
    const w = await mountForm()
    expect(w.text()).toContain('已通过')
    expect(w.text()).toContain('张三')
    expect(w.text()).not.toContain('修改组员')
    expect(w.text()).not.toContain('删除本组')
  })

  it('REJECTED 组展示理由且可改可再提交', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'REJECTED', rejectReason: '名单有误', submittedAt: '2026-08-29T10:00:00' }))
    const w = await mountForm()
    expect(w.text()).toContain('名单有误')
    expect(w.text()).toContain('修改组员')
    expect(w.text()).toContain('提交报名')
    expect(w.text()).toContain('删除本组')
  })

  it('无 cap 的组长凭手机号验证后可管理', async () => {
    const w = await mountForm()
    mock.onGet('/api/form/tk/person').reply(200, person({ teamId: 7, leader: true }))
    mock.onGet('/api/form/tk/my-team').reply(200, { id: 7, name: '组1', status: 'REJECTED',
      rejectReason: '名单有误', overLimit: false, submittedAt: '2026-08-29T10:00:00', isLeader: true,
      members: [{ employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机', isLeader: true }] })
    await (w.vm as unknown as { lookupMe: (id: string) => Promise<void> }).lookupMe('E001')
    await flushPromises()
    expect(w.text()).toContain('换机验证')
    const vm = w.vm as unknown as Record<string, unknown> & { team: { id: number } | null; verifyPhoneInput: string }
    vm.verifyPhoneInput = '13800000001'
    mock.onPost('/api/form/tk/teams/7/verify').reply(200,
      teamDetail({ status: 'REJECTED', rejectReason: '名单有误', submittedAt: '2026-08-29T10:00:00' }))
    await (vm.doVerify as () => Promise<void>)()
    expect(vm.team).not.toBeNull()
    expect(w.text()).toContain('修改组员')
    expect(JSON.parse(localStorage.getItem('muster.team.tk')!)).toEqual({ teamId: 7, cap: 'cap-7' })
  })

  it('编辑组员保存后再提交', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'REJECTED', rejectReason: '名单有误', submittedAt: '2026-08-29T10:00:00' }))
    const w = await mountForm()
    const vm = w.vm as unknown as Record<string, unknown> & { editing: boolean; team: { status: string } | null }
    await (vm.startEdit as () => void)()
    expect(vm.editing).toBe(true)
    expect(w.text()).toContain('保存修改')
    expect(w.text()).toContain('取消')

    mock.onGet('/api/form/tk/person').reply(config =>
      [200, personById(String((config.params as { employeeId: string }).employeeId))])
    await (vm.addMember as (id: string) => Promise<boolean>)('E002')
    mock.onPut(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'REJECTED', submittedAt: '2026-08-29T10:00:00',
        members: [teamDetail().members[0]!,
          { employeeId: 'E002', name: '成员E002', phone: '13800000002', department: '计算机', isLeader: false }] }))
    await (vm.onSaveDraft as () => Promise<void>)()
    expect(vm.editing).toBe(false)
    expect(mock.history.put).toHaveLength(1)

    mock.onPost(new RegExp('/api/form/tk/teams/7/submit\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'PENDING', submittedAt: 'x' }))
    await (vm.onSubmitClick as () => Promise<void>)()
    await flushPromises()
    expect(vm.team?.status).toBe('PENDING')
    expect(mock.history.post.filter(h => h.url?.includes('/submit'))).toHaveLength(1)
  })

  it('删除本组需确认并清理本地存储', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ status: 'REJECTED', rejectReason: '名单有误', submittedAt: 'x' }))
    const w = await mountForm()
    mock.onDelete(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, { ok: true })
    // onDelete 会阻塞在确认弹窗上，点击确认后才继续删除
    void (w.vm as unknown as { onDelete: () => Promise<void> }).onDelete()
    await flushPromises()
    const confirmBtn = document.querySelector('.modal-confirm .btn.danger') as HTMLElement
    expect(confirmBtn).toBeTruthy()
    confirmBtn.click()
    await flushPromises()
    expect(localStorage.getItem('muster.team.tk')).toBeNull()
    expect((w.vm as unknown as { team: unknown }).team).toBeNull()
  })
})
