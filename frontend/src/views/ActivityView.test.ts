import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { http, setToken } from '../api/http'
import { downloadFile } from '../api/download'
import { confirm } from '../components/ui/confirm'
import { toasts } from '../components/ui/toast'
import QRCode from 'qrcode'
import ActivityView from './ActivityView.vue'

// jsdom 无 canvas 2d 上下文，桩掉二维码绘制。
// 用 vi.fn(impl) 而非 mockResolvedValue：vitest 3 的 restoreAllMocks 会把
// mockResolvedValue 设的实现重置为 undefined，导致 toCanvas 返回非 Promise。
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))
// confirm 默认走「确认」分支（需要取消的用例内再单独 mockRejectedValueOnce）。
vi.mock('../components/ui/confirm', () => ({ confirm: vi.fn(async () => undefined) }))
// 下载走 a.click + blob URL，jsdom 不支持，直接模块级替换断言调用参数。
vi.mock('../api/download', () => ({ downloadFile: vi.fn(async () => undefined) }))

let mock: MockAdapter
const activity = {
  id: 1, name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, qrToken: 'qr123', exported: false, manuallyEnded: false,
  windowStatus: 'ACTIVE',
}

const wrappers: VueWrapper[] = []
function mockActivity(a: unknown = activity) {
  mock.onGet('/api/activity').reply(200, a)
  mock.onGet('/api/activity/form-url').reply(200, { url: 'http://x/form/qr123' })
}
async function mountView() {
  const w = mount(ActivityView)
  wrappers.push(w)
  await flushPromises()
  return w
}
/** UiModal 内容 Teleport 到 body，断言前等渲染/过渡就位 */
async function settle() {
  await flushPromises()
  await nextTick()
}
const body = (sel: string) => new DOMWrapper(document.querySelector(sel)!)

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('test-token')
  toasts.splice(0)
  vi.restoreAllMocks()
})

afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
})

describe('ActivityView', () => {
  it('无活动时渲染创建表单，提交补全秒并 POST', async () => {
    mock.onGet('/api/activity').reply(200, '')
    let body: Record<string, unknown> | undefined
    mock.onPost('/api/activity').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, activity]
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('创建活动')
    const inputs = wrapper.findAll('input')
    expect(inputs).toHaveLength(4)
    await inputs[0]!.setValue('迎新晚会')
    await inputs[1]!.setValue('2026-09-03T18:00') // datetime-local 无秒
    await inputs[2]!.setValue('2026-09-03T20:00')
    await inputs[3]!.setValue('8')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(body).toEqual({
      name: '迎新晚会', startTime: '2026-09-03T18:00:00', endTime: '2026-09-03T20:00:00', groupSizeLimit: 8,
    })
    expect(toasts.some(t => t.type === 'success')).toBe(true)
  })

  it('有活动时渲染 kv 信息、状态 tag 与归档 tag', async () => {
    mockActivity()
    const wrapper = await mountView()
    expect(wrapper.find('.act-name').text()).toBe('迎新晚会')
    expect(wrapper.find('.kv .v.mono').text()).toBe('2026-08-29 10:00')
    expect(wrapper.text()).toContain('5 人')
    const statusTag = wrapper.find('.act-head .tag')
    expect(statusTag.classes()).toContain('ok')
    expect(statusTag.text()).toContain('进行中')
    const archiveTag = wrapper.find('.kv .tag')
    expect(archiveTag.classes()).toContain('dim')
    expect(archiveTag.text()).toContain('未导出')
    expect(wrapper.find('[data-test="end-btn"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.cd-big').exists()).toBe(true)

    // ENDED + 已导出：状态 tag 转 dim，归档 tag 转 ok，手动结束禁用，无倒计时
    mockActivity({ ...activity, windowStatus: 'ENDED', exported: true })
    const w2 = await mountView()
    expect(w2.find('.act-head .tag').classes()).toContain('dim')
    expect(w2.find('.act-head .tag').text()).toContain('已结束')
    expect(w2.find('.kv .tag').classes()).toContain('ok')
    expect(w2.find('.kv .tag').text()).toContain('已导出归档包')
    expect(w2.find('[data-test="end-btn"]').attributes('disabled')).toBeDefined()
    expect(w2.find('.cd-big').exists()).toBe(false)
  })

  it('未开始时修改：UiModal 预填、时间可改、保存 PUT', async () => {
    mockActivity({ ...activity, windowStatus: 'NOT_STARTED' })
    let putBody: Record<string, unknown> | undefined
    mock.onPut('/api/activity').reply(config => {
      putBody = JSON.parse(config.data as string)
      return [200, activity]
    })
    const wrapper = await mountView()
    await wrapper.find('[data-test="edit-btn"]').trigger('click')
    await settle()
    const modal = document.querySelector('.ui-modal')!
    expect(modal.textContent).toContain('修改活动')
    const values = [...modal.querySelectorAll('input')].map(i => (i as HTMLInputElement).value)
    expect(values).toEqual(['迎新晚会', '2026-08-29T10:00', '2026-08-29T12:00', '5'])
    const times = [...modal.querySelectorAll('input[type="datetime-local"]')] as HTMLInputElement[]
    expect(times.every(i => !i.disabled)).toBe(true)

    await body('.ui-modal .ui-modal-body input').setValue('新年晚会')
    ;(body('[data-test="save-edit"]').element as HTMLElement).click()
    await settle()
    expect(putBody).toEqual({
      name: '新年晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00', groupSizeLimit: 5,
    })
    // 保存后关闭（等 leave 过渡时 DOM 可能仍在，断言 visible 状态即可）
    expect(wrapper.findComponent({ name: 'UiModal' }).props('visible')).toBe(false)
  })

  it('已开始后修改：时间输入禁用，保存仍回传完整秒值', async () => {
    mockActivity()
    let putBody: Record<string, unknown> | undefined
    mock.onPut('/api/activity').reply(config => {
      putBody = JSON.parse(config.data as string)
      return [200, activity]
    })
    const wrapper = await mountView()
    await wrapper.find('[data-test="edit-btn"]').trigger('click')
    await settle()
    const modal = document.querySelector('.ui-modal')!
    const times = [...modal.querySelectorAll('input[type="datetime-local"]')] as HTMLInputElement[]
    expect(times.length).toBe(2)
    expect(times.every(i => i.disabled)).toBe(true)
    await body('.ui-modal .ui-modal-body input').setValue('改名晚会')
    ;(body('[data-test="save-edit"]').element as HTMLElement).click()
    await settle()
    expect(putBody).toEqual({
      name: '改名晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00', groupSizeLimit: 5,
    })
  })

  it('手动结束：confirm 后 POST /end 并刷新', async () => {
    mockActivity()
    let ended = 0
    mock.onPost('/api/activity/end').reply(() => { ended++; return [200, ''] })
    const wrapper = await mountView()
    await wrapper.find('[data-test="end-btn"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(confirm)).toHaveBeenCalledTimes(1)
    expect(ended).toBe(1)
    expect(toasts.some(t => t.msg === '已结束')).toBe(true)
  })

  it('删除活动：confirm 后 DELETE；取消则不删', async () => {
    mockActivity()
    let deleted = 0
    mock.onDelete('/api/activity').reply(() => { deleted++; return [200, ''] })
    const wrapper = await mountView()
    await wrapper.find('[data-test="del-btn"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(confirm)).toHaveBeenCalledTimes(1)
    expect(deleted).toBe(1)

    vi.mocked(confirm).mockRejectedValueOnce(new Error('cancel'))
    await wrapper.find('[data-test="del-btn"]').trigger('click')
    await flushPromises()
    expect(deleted).toBe(1)
  })

  it('导出归档包走 downloadFile POST', async () => {
    mockActivity()
    const wrapper = await mountView()
    await wrapper.find('[data-test="export-btn"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(downloadFile)).toHaveBeenCalledWith('/api/activity/export/archive', '归档包.xlsx', 'POST')
  })

  it('报名入口渲染 canvas 二维码与表单地址', async () => {
    mockActivity()
    const wrapper = await mountView()
    expect(wrapper.find('.qr-box canvas').exists()).toBe(true)
    expect(wrapper.find('.url-line').text()).toBe('http://x/form/qr123')
    expect(QRCode.toCanvas).toHaveBeenCalled()
  })
})
