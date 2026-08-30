import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import UiModal from './UiModal.vue'
import UiDrawer from './UiDrawer.vue'
import UiPagination from './UiPagination.vue'
import ToastHost from './ToastHost.vue'
import { toast } from './toast'
import { confirm } from './confirm'

const wrappers: VueWrapper[] = []
function m(comp: unknown, opts: Record<string, unknown> = {}) {
  const w = mount(comp as never, { attachTo: document.body, ...opts })
  wrappers.push(w)
  return w
}
// 让 Teleport 内容真正落到 document.body 上再断言
async function settle() {
  await flushPromises()
  await nextTick()
}
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
})

const q = (sel: string) => document.querySelector(sel)
const buttons = () => [...document.body.querySelectorAll('button')]

describe('UiModal', () => {
  it('visible=false 不渲染任何内容', async () => {
    m(UiModal, {
      props: { visible: false, title: '标题' },
      slots: { default: '<p class="bd">内容</p>' },
    })
    await settle()
    expect(q('.ui-modal-mask')).toBeNull()
    expect(q('.ui-modal')).toBeNull()
    expect(document.body.textContent).not.toContain('内容')
  })

  it('visible=true 渲染标题、default 与 footer slot,宽度生效', async () => {
    m(UiModal, {
      props: { visible: true, title: '删除确认', width: '600px' },
      slots: { default: '<p class="bd">确定删除吗</p>', footer: '<button class="ft">确定</button>' },
    })
    await settle()
    const card = q('.ui-modal') as HTMLElement
    expect(card).toBeTruthy()
    expect(card.style.width).toBe('600px')
    expect(q('.ui-modal-title')!.textContent).toBe('删除确认')
    expect(q('.bd')!.textContent).toBe('确定删除吗')
    expect(q('.ui-modal-foot')!.textContent).toContain('确定')
  })

  it('点击遮罩 emit update:visible false;点击卡片不冒泡;默认宽 520px', async () => {
    const w = m(UiModal, { props: { visible: true, title: 'T' }, slots: { default: 'X' } })
    await settle()
    expect((q('.ui-modal') as HTMLElement).style.width).toBe('520px')
    ;(q('.ui-modal-mask') as HTMLElement).click()
    expect(w.emitted('update:visible')![0]).toEqual([false])
    ;(q('.ui-modal') as HTMLElement).click()
    expect(w.emitted('update:visible')!.length).toBe(1)
  })
})

describe('confirm', () => {
  it('点击确认按钮 Promise resolve', async () => {
    const p = confirm('确定要删除吗？')
    await settle()
    const ok = buttons().find(b => b.textContent!.includes('确认'))
    expect(ok).toBeTruthy()
    ok!.click()
    await expect(p).resolves.toBeUndefined()
    await settle()
  })

  it('点击取消按钮 reject 且不带错误对象', async () => {
    const p = confirm('确定要继续吗？')
    await settle()
    const cancel = buttons().find(b => b.textContent!.includes('取消'))
    cancel!.click()
    await expect(p).rejects.toBeUndefined()
    await settle()
  })

  it('点击遮罩同样 reject', async () => {
    const p = confirm('确认操作')
    await settle()
    ;(q('.ui-modal-mask') as HTMLElement).click()
    await expect(p).rejects.toBeUndefined()
    await settle()
  })

  it('type=danger 渲染红色确认按钮', async () => {
    const p = confirm('删除后无法恢复', '危险操作', 'danger')
    await settle()
    const ok = q('.ui-modal-foot .btn.danger') as HTMLElement
    expect(ok).toBeTruthy()
    expect(ok.textContent).toContain('确认')
    ok.click()
    await p.catch(() => {})
    await settle()
  })
})

describe('toast', () => {
  it('success 消息渲染并 3 秒后自动消失', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      toast.success('已保存')
      m(ToastHost)
      await nextTick()
      expect(document.body.textContent).toContain('已保存')
      vi.advanceTimersByTime(3000)
      await nextTick()
      expect(document.body.textContent).not.toContain('已保存')
    } finally {
      vi.useRealTimers()
    }
  })

  it('error/warning 分别渲染对应类型 class', async () => {
    toast.error('操作失败')
    toast.warning('请注意')
    m(ToastHost)
    await settle()
    expect(q('.toast.toast-error')!.textContent).toContain('操作失败')
    expect(q('.toast.toast-warning')!.textContent).toContain('请注意')
  })
})

describe('UiDrawer', () => {
  it('渲染 title/default/footer slot,size 生效,点遮罩关闭', async () => {
    const w = m(UiDrawer, {
      props: { visible: true, title: '组14', size: '480px' },
      slots: { default: '<p class="dr-test">抽屉内容</p>', footer: '<button class="f">操作</button>' },
    })
    await settle()
    const drawer = q('.drawer') as HTMLElement
    expect(drawer).toBeTruthy()
    expect(drawer.style.width).toBe('480px')
    expect(q('.dr-head')!.textContent).toContain('组14')
    expect(q('.dr-test')!.textContent).toBe('抽屉内容')
    expect(q('.dr-foot')!.textContent).toContain('操作')
    ;(q('.ui-drawer-mask') as HTMLElement).click()
    expect(w.emitted('update:visible')![0]).toEqual([false])
  })
})

describe('UiPagination', () => {
  it('total=25/size=10 渲染 3 页,点第 2 页 emit change(2)', async () => {
    const w = mount(UiPagination, { props: { total: 25, page: 1, size: 10 } })
    wrappers.push(w)
    const nums = w
      .findAll('.pg')
      .filter(b => !b.classes().includes('pg-nav') && !b.classes().includes('pg-ellipsis'))
    expect(nums.map(b => b.text())).toEqual(['1', '2', '3'])
    await nums[1]!.trigger('click')
    expect(w.emitted('change')![0]).toEqual([2])
  })

  it('超过 7 页出现省略号,active 页高亮', () => {
    const w = mount(UiPagination, { props: { total: 100, page: 5, size: 10 } })
    wrappers.push(w)
    expect(w.text()).toContain('…')
    expect(w.find('.pg.active').text()).toBe('5')
  })

  it('‹ › 翻页 emit change,首页 ‹ 禁用', async () => {
    const w = mount(UiPagination, { props: { total: 25, page: 1, size: 10 } })
    wrappers.push(w)
    const navs = w.findAll('.pg-nav')
    expect(navs.length).toBe(2)
    expect(navs[0]!.attributes('disabled')).toBeDefined()
    await navs[1]!.trigger('click')
    expect(w.emitted('change')![0]).toEqual([2])
  })
})
