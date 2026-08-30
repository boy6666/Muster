import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import RosterView from './RosterView.vue'

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

async function mountView() {
  const wrapper = mount(RosterView, { global: { plugins: [createPinia(), ElementPlus] } })
  await flushPromises()
  return wrapper
}

/** 点击 body 级 ElMessageBox 的确认按钮；关闭的弹窗不卸载，须取最新一个 */
async function confirmBox() {
  await flushPromises()
  const boxes = document.querySelectorAll('.el-message-box')
  const btn = boxes[boxes.length - 1]!.querySelector('.el-button--primary') as HTMLElement
  expect(btn).toBeTruthy()
  btn.click()
  await flushPromises()
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  mock.onGet('/api/roster').reply(200, { total: 2, records })
})

describe('RosterView', () => {
  it('列表渲染四列基础信息 + 组别/组长/状态展示列', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    for (const label of ['员工编号', '姓名', '手机号', '部门', '组别', '组长', '状态']) {
      expect(text).toContain(label)
    }
    expect(text).toContain('E001')
    expect(text).toContain('组1')
    expect(text).toContain('已参加')
    expect(text).toContain('未参加')
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
    ;(wrapper.vm as unknown as { keyword: string }).keyword = '张'
    await (wrapper.vm as unknown as { load: () => Promise<void> }).load()
    expect(params).toMatchObject({ keyword: '张', page: 1, size: 10 })
  })

  it('添加对话框四个字段，员工编号必填不发请求', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    ;(wrapper.vm as unknown as { openAdd: () => void }).openAdd()
    await flushPromises()
    const vm = wrapper.vm as unknown as { addForm: Record<string, string>; submitAdd: () => Promise<void> }
    vm.addForm = { employeeId: '', name: '张三', phone: '13800000001', department: '计算机' }
    await vm.submitAdd()
    expect(mock.history.post).toHaveLength(0)

    vm.addForm = { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' }
    await vm.submitAdd()
    const post = mock.history.post[0]!
    expect(JSON.parse(post.data as string)).toEqual(
      { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' })
  })

  it('编辑按钮预填对话框并 PUT 保存', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      records: Array<{ id: number; employeeId: string; name: string; phone: string;
        department: string; teamName: string | null; leaderName: string | null;
        isLeader: boolean; participated: boolean }>
      startEdit: (row: Record<string, unknown>) => void
      editVisible: boolean
      editForm: Record<string, string>
      submitEdit: () => Promise<void>
    }
    vm.startEdit(vm.records[0]!)
    expect(vm.editVisible).toBe(true)
    expect(vm.editForm).toEqual(
      { employeeId: 'E001', name: '张三', phone: '13800000001', department: '计算机' })
    vm.editForm.department = '数学'
    await vm.submitEdit()
    const put = mock.history.put[0]!
    expect(put.url).toBe('/api/roster/1')
    expect(JSON.parse(put.data as string).department).toBe('数学')
  })

  it('一键清空：双重确认后删除并提示清空人数', async () => {
    mock.onDelete('/api/roster').reply(200, { deleted: 5 })
    const wrapper = await mountView()
    void (wrapper.vm as unknown as { clearRoster: () => Promise<void> }).clearRoster()
    await confirmBox()
    await confirmBox()
    expect(mock.history.delete.filter(h => h.url === '/api/roster')).toHaveLength(1)
    expect(document.body.textContent).toContain('已清空 5 人')
  })

  it('一键清空 409：仅提示后端 message', async () => {
    // body 级 toast 跨用例残留，按出现次数断言"本次未弹成功提示"
    const clearedCount = () => (document.body.textContent ?? '').split('已清空').length - 1
    const before = clearedCount()
    mock.onDelete('/api/roster').reply(409,
      { code: 'ARCHIVE_REQUIRED', message: '已存在报名组，不能清空花名册' })
    const wrapper = await mountView()
    void (wrapper.vm as unknown as { clearRoster: () => Promise<void> }).clearRoster()
    await confirmBox()
    await confirmBox()
    expect(document.body.textContent).toContain('已存在报名组，不能清空花名册')
    expect(clearedCount()).toBe(before)
  })
})
