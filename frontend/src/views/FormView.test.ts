import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Vant from 'vant'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import FormView from './FormView.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { token: 'tk' } }) }))

let mock: MockAdapter
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

async function mountForm() {
  const wrapper = mount(FormView, { global: { plugins: [Vant] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
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
    localStorage.setItem('muster.team.tk', '7')
    mock.onGet('/api/form/tk').reply(200, info)
    mock.onGet('/api/form/tk/teams/7').reply(200,
      { id: 7, name: '组2', status: 'PENDING', rejectReason: null, overLimit: false,
        submittedAt: '2026-08-29T10:00:00',
        members: [{ name: '李四', phone: '13800000002', department: '外语' }] })
    const wrapper = await mountForm()
    expect(wrapper.text()).toContain('组2')
    expect(wrapper.text()).toContain('修改组员')
  })
})
