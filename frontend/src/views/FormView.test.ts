import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Vant from 'vant'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import FormView from './FormView.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { token: 'tk' } }) }))

let mock: MockAdapter
let wrapper: VueWrapper | null = null
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

async function mountForm() {
  wrapper = mount(FormView, { global: { plugins: [Vant] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

// Vant 的占位高度测量定时器不随测试环境自动清理，必须显式卸载组件
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('FormView', () => {
  it('活动未开始时显示占位提示', async () => {
    mock.onGet('/api/form/tk').reply(200, { ...info, windowStatus: 'NOT_STARTED' })
    const wrapper = await mountForm()
    expect(wrapper.text()).toContain('活动未开始')
  })

  it('进行中时展示建组表单', async () => {
    mock.onGet('/api/form/tk').reply(200, info)
    const wrapper = await mountForm()
    expect(wrapper.text()).toContain('组长手机号')
  })

  it('已提交过的组展示本组详情', async () => {
    localStorage.setItem('muster.team.tk', JSON.stringify({ teamId: 7, cap: 'cap-7' }))
    mock.onGet('/api/form/tk').reply(200, info)
    mock.onGet(new RegExp('/api/form/tk/teams/7\\?cap=cap-7$')).reply(200,
      { id: 7, name: '组2', status: 'PENDING', rejectReason: null, capToken: 'cap-7',
        overLimit: false, submittedAt: '2026-08-29T10:00:00',
        members: [{ name: '李四', phone: '13800000002', department: '外语' }] })
    const wrapper = await mountForm()
    expect(wrapper.text()).toContain('组2')
    expect(wrapper.text()).toContain('修改组员')
  })
})
