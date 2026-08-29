import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import { router } from '../router'
import RosterView from './RosterView.vue'

let mock: MockAdapter
async function mountView() {
  const wrapper = mount(RosterView, { global: { plugins: [createPinia(), ElementPlus, router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
})

describe('RosterView', () => {
  it('搜索结果渲染表格', async () => {
    mock.onGet('/api/roster').reply(200, {
      total: 2,
      records: [
        { id: 1, name: '张三', phone: '13800000001', department: '计算机' },
        { id: 2, name: '李四', phone: '13800000002', department: '外语' },
      ],
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('外语')
  })

  it('搜索关键字作为查询参数', async () => {
    let params: Record<string, unknown> = {}
    mock.onGet('/api/roster').reply(config => {
      params = config.params as Record<string, unknown>
      return [200, { total: 0, records: [] }]
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).keyword = '张'
    await (wrapper.vm as any).load()
    expect(params).toMatchObject({ keyword: '张', page: 1, size: 10 })
  })

  it('添加成功刷新列表', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    let body: Record<string, unknown> | undefined
    mock.onPost('/api/roster').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, { id: 9, name: '张三', phone: '13800000001', department: '计算机' }]
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).addForm = { name: '张三', phone: '13800000001', department: '计算机' }
    await (wrapper.vm as any).submitAdd()
    expect(body).toEqual({ name: '张三', phone: '13800000001', department: '计算机' })
  })

  it('重复手机号提交后错误可见', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    mock.onPost('/api/roster').reply(400, {
      code: 'PHONE_DUPLICATE', message: '手机号已在花名册中：13800000001',
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).addForm = { name: '张三', phone: '13800000001', department: '计算机' }
    await (wrapper.vm as any).submitAdd()
    await flushPromises()
    // ElMessage 挂在 body 级 overlay，不在组件树内
    expect(document.body.textContent).toContain('手机号已在花名册中')
  })

  it('手机号格式不通过不发请求', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    ;(wrapper.vm as any).addForm = { name: '张三', phone: '138', department: '计算机' }
    await (wrapper.vm as any).submitAdd()
    expect(mock.history.some(h => h.method === 'post' && h.url === '/api/roster')).toBe(false)
  })
})
