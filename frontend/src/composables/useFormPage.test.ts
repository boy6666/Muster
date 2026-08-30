import { describe, it, expect, beforeEach } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useFormPage } from './useFormPage'

let mock: MockAdapter
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

function person(overrides: Record<string, unknown> = {}) {
  return { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机',
    teamId: null, leader: false, ...overrides }
}

function teamDetail(overrides: Record<string, unknown> = {}) {
  return { id: 7, name: '组2', status: 'PENDING', rejectReason: null, capToken: 'cap-7',
    overLimit: false, submittedAt: 'x',
    members: [{ employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机', isLeader: true }],
    ...overrides }
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  mock.onGet('/api/form/tk').reply(200, info)
})

const TK = '/api/form/tk'

describe('useFormPage', () => {
  it('lookupMe 按员工编号查询并回显', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(config => {
      expect(config.params).toEqual({ employeeId: 'E001' })
      return [200, person()]
    })
    await page.lookupMe('E001')
    expect(page.me.value).toMatchObject({ employeeId: 'E001', name: '张三' })
    expect(page.meError.value).toBe('')
  })

  it('查询报错写入 meError', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(404, { code: 'PERSON_NOT_FOUND', message: '花名册中没有该员工编号' })
    await page.lookupMe('E999')
    expect(page.me.value).toBeNull()
    expect(page.meError.value).toBe('花名册中没有该员工编号')
  })

  it('已在组时查询返回 teamId → 自动拉取 my-team', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(200, person({ teamId: 7, leader: true }))
    mock.onGet(`${TK}/my-team`).reply(config => {
      expect(config.params).toEqual({ employeeId: 'E001' })
      return [200, { id: 7, name: '组1', status: 'PENDING', rejectReason: null, overLimit: false,
        submittedAt: 'x', isLeader: true, members: [] }]
    })
    await page.lookupMe('E001')
    expect(page.teamView.value?.id).toBe(7)
    expect(page.teamView.value?.isLeader).toBe(true)
  })

  it('createDraft POST 新 body 并保存 {teamId, cap} 到 localStorage', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(config =>
      [200, person(config.params.employeeId === 'E001' ? {} :
        { employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语' })])
    await page.addMember('E001')
    await page.addMember('E002')
    mock.onPost(`${TK}/teams`).reply(200, teamDetail({ id: 9, name: '组1', capToken: 'cap-9' }))
    await page.createDraft()
    expect(JSON.parse(mock.history.post[0]!.data as string))
      .toEqual({ leaderEmployeeId: 'E001', memberEmployeeIdList: ['E001', 'E002'] })
    expect(JSON.parse(localStorage.getItem('muster.team.tk')!)).toEqual({ teamId: 9, cap: 'cap-9' })
    expect(page.team.value?.id).toBe(9)
    expect(page.cap.value).toBe('cap-9')
  })

  it('createDraft 409 冲突 → conflicts 填充（employeeId 字段）', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(200, person())
    await page.addMember('E001')
    mock.onPost(`${TK}/teams`).reply(409, {
      code: 'CONFLICT', message: '以下成员已在其他组',
      data: [{ employeeId: 'E001', name: '张三', teamName: '组3' }],
    })
    await page.createDraft()
    expect(page.conflicts.value).toHaveLength(1)
    expect(page.conflicts.value[0]!.employeeId).toBe('E001')
    expect(page.conflicts.value[0]!.teamName).toBe('组3')
  })

  it('submit POST /submit?cap= body {leaderPhone} → team 更新', async () => {
    const page = useFormPage('tk')
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, teamDetail({ status: 'DRAFT' }))
    await page.load()
    mock.onPost(new RegExp('/api/form/tk/teams/7/submit\\?cap=cap-7$')).reply(config => {
      expect(JSON.parse(config.data)).toEqual({ leaderPhone: '13800000001' })
      return [200, teamDetail({ status: 'PENDING' })]
    })
    await page.submit('13800000001')
    expect(page.team.value?.status).toBe('PENDING')
  })

  it('save PUT /teams/{id}?cap= → team 更新（状态不变）', async () => {
    const page = useFormPage('tk')
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, teamDetail({ status: 'DRAFT' }))
    await page.load()
    await page.startEdit()
    page.members.value.push({ employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语', isLeader: false })
    mock.onPut(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(config => {
      expect(JSON.parse(config.data)).toEqual({
        leaderEmployeeId: 'E001', memberEmployeeIdList: ['E001', 'E002'] })
      return [200, teamDetail({ status: 'DRAFT',
        members: [teamDetail().members[0]!,
          { employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语', isLeader: false }] })]
    })
    await page.save()
    expect(page.editing.value).toBe(false)
    expect(page.team.value?.status).toBe('DRAFT')
    expect(page.team.value?.members).toHaveLength(2)
  })

  it('verify POST /verify → cap 存 localStorage', async () => {
    const page = useFormPage('tk')
    mock.onPost(`${TK}/teams/7/verify`).reply(200, teamDetail({ capToken: 'cap-new', status: 'REJECTED', rejectReason: '名单有误' }))
    await page.verify(7, '13800000001')
    expect(JSON.parse(mock.history.post[0]!.data as string)).toEqual({ leaderPhone: '13800000001' })
    expect(page.cap.value).toBe('cap-new')
    expect(JSON.parse(localStorage.getItem('muster.team.tk')!)).toEqual({ teamId: 7, cap: 'cap-new' })
    expect(page.team.value?.status).toBe('REJECTED')
  })

  it('deleteTeam DELETE → 清 localStorage 并复位状态', async () => {
    const page = useFormPage('tk')
    mock.onGet(`${TK}/person`).reply(200, person({ teamId: 7, leader: true }))
    mock.onGet(`${TK}/my-team`).reply(200, { id: 7, name: '组1', status: 'REJECTED', rejectReason: '名单有误',
      overLimit: false, submittedAt: 'x', isLeader: true, members: [] })
    await page.lookupMe('E001')
    expect(page.teamView.value).not.toBeNull()
    page.cap.value = 'cap-7'
    mock.onDelete(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, { ok: true })
    await page.deleteTeam()
    expect(localStorage.getItem('muster.team.tk')).toBeNull()
    expect(page.team.value).toBeNull()
    expect(page.teamView.value).toBeNull()
    expect(page.me.value).toBeNull()
    expect(page.members.value).toHaveLength(0)
  })

  it('旧 localStorage 结构（无 cap 字段）被安全忽略', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7 }))
    const page = useFormPage('tk')
    await page.load()
    expect(page.team.value).toBeNull()
    expect(localStorage.getItem('muster.team.tk')).toBeNull()
    expect(mock.history.filter(h => h.url?.includes('/teams/')).length).toBe(0)
  })
})
