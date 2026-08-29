import { describe, it, expect, beforeEach } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useFormPage } from './useFormPage'

let mock: MockAdapter
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

function teamDetail(overrides: Record<string, unknown> = {}) {
  return { id: 7, name: '组2', status: 'PENDING', rejectReason: null, capToken: 'cap-7',
    overLimit: false, submittedAt: 'x',
    members: [{ name: '李四', phone: '13800000002', department: '外语' }], ...overrides }
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

function setup() {
  mock.onGet('/api/form/tk').reply(200, info)
  return useFormPage('tk')
}

describe('useFormPage', () => {
  it('组长手机 11 位才触发回显并自动入组', async () => {
    const page = setup()
    await page.onLeaderPhone('138')
    expect(mock.history.filter(h => h.url?.includes('/person')).length).toBe(0)
    mock.onGet(new RegExp('/api/form/tk/person')).reply(200,
      { name: '张三', phone: '13800000001', department: '计算机' })
    await page.onLeaderPhone('13800000001')
    expect(page.leader.value).toMatchObject({ name: '张三' })
    expect(page.members.value.map(m => m.phone)).toContain('13800000001')
  })

  it('重复成员被拒', async () => {
    const page = setup()
    mock.onGet(new RegExp('/api/form/.*/person.*')).reply(200,
      { name: '张三', phone: '13800000001', department: '计算机' })
    await page.onLeaderPhone('13800000001')
    const added = await page.addMember('13800000001')
    expect(added).toBe(false)
    expect(page.members.value).toHaveLength(1)
  })

  it('提交成功保存 teamId+capToken 到 localStorage', async () => {
    const page = setup()
    mock.onGet(new RegExp('/api/form/.*/person.*')).reply(200,
      { name: '张三', phone: '13800000001', department: '计算机' })
    await page.onLeaderPhone('13800000001')
    mock.onPost('/api/form/tk/teams').reply(200, teamDetail({
      id: 9, name: '组1', capToken: 'cap-9',
      members: [{ name: '张三', phone: '13800000001', department: '计算机' }],
    }))
    await page.submit()
    expect(JSON.parse(localStorage.getItem('muster.team.tk')!)).toEqual({ teamId: 9, cap: 'cap-9' })
    expect(page.team.value?.id).toBe(9)
  })

  it('409 冲突展示冲突明细', async () => {
    const page = setup()
    mock.onGet(new RegExp('/api/form/.*/person.*')).reply(200,
      { name: '张三', phone: '13800000001', department: '计算机' })
    await page.onLeaderPhone('13800000001')
    mock.onPost('/api/form/tk/teams').reply(409, {
      code: 'CONFLICT', message: '以下成员已在其他组',
      data: [{ phone: '13800000001', name: '张三', teamName: '组3' }],
    })
    await page.submit()
    expect(page.conflicts.value).toHaveLength(1)
    expect(page.conflicts.value[0]!.teamName).toBe('组3')
  })

  it('已有 teamId 时 load 携带 cap 拉取本组详情', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, teamDetail())
    const page = setup()
    await page.load()
    expect(page.team.value?.id).toBe(7)
    expect(page.team.value?.name).toBe('组2')
  })

  it('组长改组 PUT 携带 cap', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200, teamDetail())
    const page = setup()
    await page.load()
    await page.startEdit()
    mock.onPut(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      teamDetail({ members: [{ name: '王五', phone: '13800000003', department: '体育' }] }))
    await page.saveEdit()
    expect(page.editing.value).toBe(false)
    expect(page.team.value?.members.map(m => m.phone)).toEqual(['13800000003'])
  })

  it('旧格式 localStorage（纯 teamId 数字）被清理且不请求详情', async () => {
    localStorage.setItem('muster.team.tk', '7')
    const page = setup()
    await page.load()
    expect(page.team.value).toBeNull()
    expect(localStorage.getItem('muster.team.tk')).toBeNull()
    expect(mock.history.filter(h => h.url?.includes('/teams/')).length).toBe(0)
  })
})
