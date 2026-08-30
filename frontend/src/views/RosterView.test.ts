import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import { toasts } from '../components/ui/toast'
import { confirm } from '../components/ui/confirm'
import type { PersonRow } from '../api/types'
import RosterView from './RosterView.vue'

// confirm 共享弹窗 mock 为直接确认;需要模拟取消的用例用 mockRejectedValueOnce
vi.mock('../components/ui/confirm', () => ({ confirm: vi.fn().mockResolvedValue(undefined) }))

let mock: MockAdapter

function row(overrides: Record<string, unknown> = {}) {
  return { id: 1, employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机',
    teamId: 7, teamName: '组1', leaderName: '张三', isLeader: true, participated: true, ...overrides }
}

const records = [
  row(),
  row({ id: 2, employeeId: 'E002', name: '李四', phone: '13800000002', department: '外语',
    teamId: null, teamName: null, leaderName: null, isLeader: false, participated: false }),
]

type Exposed = {
  keyword: string
  page: number
  total: number
  records: PersonRow[]
  form: { employeeId: string; name: string; phone: string; department: string }
  formVisible: boolean
  editingId: number | null
  load: () => Promise<void>
  openAdd: () => void
  submitForm: () => Promise<void>
  startEdit: (row: PersonRow) => void
  clearRoster: () => Promise<void>
  remove: (row: PersonRow) => Promise<void>
}

const wrappers: VueWrapper[] = []
async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(RosterView)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

const vm = (wrapper: VueWrapper) => wrapper.vm as unknown as Exposed

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  toasts.splice(0, toasts.length) // toast 为模块级共享数组,清掉跨用例残留
  vi.mocked(confirm).mockReset()
  vi.mocked(confirm).mockResolvedValue(undefined)
  mock.onGet('/api/roster').reply(200, { total: 2, records })
})

afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
})

describe('RosterView', () => {
  it('列表渲染四列基础信息 + 组别/组长/状态 tag', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    for (const label of ['员工编号', '姓名', '手机号', '部门', '组别', '组长', '状态', '操作']) {
      expect(text).toContain(label)
    }
    expect(text).toContain('E001')
    expect(text).toContain('组1')
    expect(wrapper.find('.tag.ok').text()).toBe('已参加')
    expect(wrapper.find('.tag.dim').text()).toBe('未参加')
  })

  it('搜索占位提示四类可搜索字段', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('input').attributes('placeholder')).toBe('员工编号 / 姓名 / 手机号 / 部门')
  })

  it('搜索关键字作为查询参数', async () => {
    let params: Record<string, unknown> = {}
    mock.onGet('/api/roster').reply(config => {
      params = config.params as Record<string, unknown>
      return [200, { total: 0, records: [] }]
    })
    const wrapper = await mountView()
    const v = vm(wrapper)
    v.keyword = '张'
    await v.load()
    expect(params).toMatchObject({ keyword: '张', page: 1, size: 10 })
  })

  it('添加弹窗四个字段，员工编号必填不发请求', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    const v = vm(wrapper)
    v.openAdd()
    await flushPromises()
    expect(v.formVisible).toBe(true)

    v.form = { employeeId: '', name: '张三', phone: '13800000001', department: '计算机' }
    await v.submitForm()
    expect(mock.history.post).toHaveLength(0)
    expect(toasts.some(t => t.type === 'warning' && t.msg === '请输入员工编号')).toBe(true)

    v.form = { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' }
    await v.submitForm()
    const post = mock.history.post[0]!
    expect(JSON.parse(post.data as string)).toEqual(
      { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' })
  })

  it('编辑按钮预填弹窗并 PUT 保存', async () => {
    const wrapper = await mountView()
    const v = vm(wrapper)
    v.startEdit(v.records[0]!)
    expect(v.formVisible).toBe(true)
    expect(v.form).toEqual(
      { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' })
    v.form.department = '数学'
    await v.submitForm()
    const put = mock.history.put[0]!
    expect(put.url).toBe('/api/roster/1')
    expect(JSON.parse(put.data as string).department).toBe('数学')
  })

  it('一键清空：双重确认后删除并提示清空人数', async () => {
    mock.onDelete('/api/roster').reply(200, { deleted: 5 })
    const wrapper = await mountView()
    void vm(wrapper).clearRoster()
    await flushPromises()
    expect(mock.history.delete.filter(h => h.url === '/api/roster')).toHaveLength(1)
    expect(toasts.some(t => t.type === 'success' && t.msg === '已清空 5 人')).toBe(true)
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(vi.mocked(confirm).mock.calls[0]![0]).toBe('将清空当前活动全部花名册')
    expect(vi.mocked(confirm).mock.calls[1]![0]).toContain('不可恢复')
  })

  it('一键清空 409：仅提示后端 message，不再弹确认框', async () => {
    mock.onDelete('/api/roster').reply(409,
      { code: 'ARCHIVE_REQUIRED', message: '已存在报名组，不能清空花名册' })
    const wrapper = await mountView()
    void vm(wrapper).clearRoster()
    await flushPromises()
    expect(toasts.some(t => t.type === 'error' && t.msg === '已存在报名组，不能清空花名册')).toBe(true)
    expect(toasts.some(t => t.msg.includes('已清空'))).toBe(false)
    expect(mock.history.delete.filter(h => h.url === '/api/roster')).toHaveLength(1)
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('删除行：confirm 提示连带移除后 DELETE', async () => {
    mock.onDelete('/api/roster/1').reply(200, {})
    const wrapper = await mountView()
    const v = vm(wrapper)
    void v.remove(v.records[0]!)
    await flushPromises()
    expect(confirm).toHaveBeenCalledWith('删除 张三？已入组的成员将一并移除', '删除人员', 'warning')
    expect(mock.history.delete.filter(h => h.url === '/api/roster/1')).toHaveLength(1)
    expect(toasts.some(t => t.type === 'success' && t.msg === '已删除')).toBe(true)
  })
})
