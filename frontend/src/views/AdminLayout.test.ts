import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken } from '../api/http'
import { toasts } from '../components/ui/toast'
import { useAuthStore } from '../stores/auth'
import AdminLayout from './AdminLayout.vue'

// 不 import src/router.ts —— 会拉入全部真实视图(其他任务并行重写中);
// 这里自建内存路由 + stub 占位子组件。
const stub = (name: string) => ({ template: `<div data-stub="${name}">${name}</div>` })

function buildRouter(): ReturnType<typeof createRouter> {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: AdminLayout,
        children: [
          { path: 'home', component: stub('home'), meta: { title: '实时统计' } },
          { path: 'activity', component: stub('activity'), meta: { title: '活动管理' } },
          { path: 'roster', component: stub('roster'), meta: { title: '花名册' } },
          { path: 'teams', component: stub('teams'), meta: { title: '组管理' } },
          { path: 'audit', component: stub('audit'), meta: { title: '审计日志' } },
        ],
      },
      { path: '/login', component: stub('login') },
    ],
  })
}

const q = (sel: string) => document.querySelector(sel)

let wrapper: VueWrapper | null = null
let router: ReturnType<typeof createRouter>
let mock: MockAdapter

// 经 <router-view/> 挂载(与 App.vue 一致):直接 mount(AdminLayout) 会使其内层
// router-view 在 depth 0 再次渲染自身(它同时是 /admin 路由的 component)。
const HostApp = defineComponent({ template: '<router-view />' })

async function mountLayout() {
  router = buildRouter()
  await router.push('/admin/home')
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  wrapper = mount(HostApp, { global: { plugins: [router, pinia] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

function openAdminMenu() {
  return wrapper!.find('.admin-chip').trigger('click')
}

function menuItems() {
  return [...wrapper!.findAll('.admin-menu-item')]
}

async function openPasswordModal() {
  await openAdminMenu()
  const item = menuItems().find(i => i.text().includes('修改密码'))!
  await item.trigger('click')
  await flushPromises()
  await nextTick()
}

function setModalPassword(idx: number, value: string) {
  const el = document.querySelectorAll('.ui-modal input[type="password"]')[idx] as HTMLInputElement
  el.value = value
  el.dispatchEvent(new Event('input'))
}

function clickSave() {
  const save = [...document.querySelectorAll('.ui-modal-foot button')].find(b =>
    b.textContent!.includes('保存'),
  ) as HTMLElement
  save.click()
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken('t-admin')
  toasts.splice(0, toasts.length)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  mock.restore()
})

describe('AdminLayout', () => {
  it('渲染 brand(点将台/MUSTER CONSOLE)与 5 个导航项', async () => {
    await mountLayout()
    const brand = wrapper!.find('.brand')
    expect(brand.text()).toContain('点将台')
    expect(brand.text()).toContain('MUSTER CONSOLE')
    const navs = wrapper!.findAll('.nav-item')
    expect(navs).toHaveLength(5)
    expect(navs.map(n => n.text().trim())).toEqual([
      '实时统计', '活动管理', '花名册', '组管理', '审计日志',
    ])
    expect(navs.map(n => n.attributes('href'))).toEqual([
      '/admin/home', '/admin/activity', '/admin/roster', '/admin/teams', '/admin/audit',
    ])
  })

  it('当前路由导航项 active 高亮,点导航切换路由且 crumb 跟随 meta.title', async () => {
    await mountLayout()
    const navs = wrapper!.findAll('.nav-item')
    expect(navs[0]!.classes()).toContain('active')
    await navs[3]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/teams')
    expect(wrapper!.find('.crumb').text()).toBe('组管理')
  })

  it('side-foot 渲染系统状态', async () => {
    await mountLayout()
    const foot = wrapper!.find('.side-foot').text()
    expect(foot).toContain('SYSTEM v1.0')
    expect(foot).toContain('单活动模式')
    expect(foot).toContain('READY')
  })

  it('topbar 含 LIVE 徽标与时钟(HH:MM:SS 且每秒刷新)', async () => {
    vi.useFakeTimers()
    try {
      await mountLayout()
      expect(wrapper!.find('.live-badge').text()).toContain('LIVE')
      const t0 = wrapper!.find('.clock').text()
      expect(t0).toMatch(/^\d{2}:\d{2}:\d{2}$/)
      vi.advanceTimersByTime(1000)
      await nextTick()
      expect(wrapper!.find('.clock').text()).not.toBe(t0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('点管理员菜单外部区域收起下拉', async () => {
    await mountLayout()
    await openAdminMenu()
    expect(wrapper!.find('.admin-menu').exists()).toBe(true)
    await wrapper!.find('.crumb').trigger('click')
    await nextTick()
    expect(wrapper!.find('.admin-menu').exists()).toBe(false)
  })

  it('点退出登录:token 清空且跳转 /login', async () => {
    await mountLayout()
    await openAdminMenu()
    const out = menuItems().find(i => i.text().includes('退出登录'))!
    expect(out).toBeTruthy()
    await out.trigger('click')
    await flushPromises()
    expect(getToken()).toBeNull()
    expect(useAuthStore().token).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('修改密码弹窗可开,含旧密码/新密码/确认新密码三个输入', async () => {
    await mountLayout()
    expect(q('.ui-modal')).toBeNull()
    await openPasswordModal()
    expect(q('.ui-modal')).toBeTruthy()
    expect(q('.ui-modal-title')!.textContent).toContain('修改密码')
    expect(q('.ui-modal')!.querySelectorAll('input[type="password"]')).toHaveLength(3)
  })

  it('新密码少于 6 位提示且不调接口', async () => {
    await mountLayout()
    await openPasswordModal()
    setModalPassword(0, 'old-pass')
    setModalPassword(1, '123')
    setModalPassword(2, '123')
    clickSave()
    await flushPromises()
    expect(toasts.some(t => t.msg === '新密码至少 6 位')).toBe(true)
    expect(mock.history.put).toHaveLength(0)
  })

  it('两次新密码不一致提示且不调接口', async () => {
    await mountLayout()
    await openPasswordModal()
    setModalPassword(0, 'old-pass')
    setModalPassword(1, 'new-pass-1')
    setModalPassword(2, 'different')
    clickSave()
    await flushPromises()
    expect(toasts.some(t => t.msg === '两次输入的新密码不一致')).toBe(true)
    expect(mock.history.put).toHaveLength(0)
  })

  it('修改密码成功:调 PUT /api/auth/password、toast 提示并关闭弹窗', async () => {
    mock.onPut('/api/auth/password').reply(200, { code: 'OK', message: 'ok' })
    await mountLayout()
    await openPasswordModal()
    setModalPassword(0, 'old-pass')
    setModalPassword(1, 'new-pass-1')
    setModalPassword(2, 'new-pass-1')
    clickSave()
    await flushPromises()
    expect(mock.history.put).toHaveLength(1)
    expect(JSON.parse(mock.history.put[0]!.data!)).toEqual({
      oldPassword: 'old-pass',
      newPassword: 'new-pass-1',
    })
    expect(toasts.some(t => t.msg === '密码已修改')).toBe(true)
    await new Promise(r => setTimeout(r, 300))
    expect(q('.ui-modal')).toBeNull()
  })
})
