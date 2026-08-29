# Muster·点将台 — 前端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现管理后台（Vue3 + Element Plus：登录、活动管理、花名册、组审核、实时统计、审计日志）与移动端扫码报名 H5（Vant：自动回显、组长建组/改组），消费已冻结的 `docs/api.md` 全部接口。

**Architecture:** 单个 Vite 应用两条路由树：`/admin/**`（Element Plus 桌面后台，带登录守卫）与 `/form/:token`（Vant 移动 H5，匿名）。所有请求走统一的 axios 实例（自动附带 Bearer、401 清 token 跳登录、错误统一剥成 `{code,message,data}` 抛出）；实时统计用可注入工厂的 WebSocket 组合式函数；文件下载统一走 blob 助手。业务规则不在前端重复实现，只做体验层校验，4xx/409 一律展示后端中文 message。

**Tech Stack:** Vue 3.5 · Vite 7 · TypeScript · vue-router 4 · Pinia 3 · axios 1 · Element Plus 2 · Vant 4 · qrcode 1 · Vitest 3 + @vue/test-utils 2 + jsdom + axios-mock-adapter（测试统一打在 http 实例上）· Node ≥20

## Global Constraints

- 后端 API 契约以 `docs/api.md` 为准，不得擅自改字段名；`GET /api/activity` 无活动时是 **200 空体**（`resp.data === ''`），须按"无活动"处理
- H5 报名页路由必须是 `/form/:token`（后端 `formUrl() = form-base-url + /form/{qrToken}`）
- 自动回显只在 **完整 11 位手机号** 时触发（调 `GET /api/form/{token}/person?phone=`），部分输入绝不请求
- 首页统计仅在活动 `windowStatus === 'ACTIVE'` 时显示，否则显示占位文案（grilling 决议：活动开始后显示，其他时间不显示）
- 所有错误提示直接展示后端 `message`（已是中文）；禁止吞错误
- JWT 存 `localStorage['muster.token']`；401 时清 token 跳 `/login`，但 `/api/auth/login` 自身的 401（AUTH_FAILED）不触发跳转，仅展示错误
- 下载（模板/导出/归档）必须带 Authorization → 走 axios blob + `URL.createObjectURL`
- WebSocket `/ws/stats?token=<JWT>`，帧格式同 `GET /api/stats`：`{total,joined,notJoined,teamCount,pendingTeamCount}`
- 移动端允许超上限提交，提交前 `Dialog.confirm` 提示超出人数；409 CONFLICT 时展示 `data` 冲突明细
- 提交时间等 `LocalDateTime` 序列化为 `2026-08-29T10:00:00`；展示用 `YYYY-MM-DD HH:mm`，日期控件 `value-format="YYYY-MM-DDTHH:mm:ss"`
- 组件测试一律通过 `axios-mock-adapter` 打 `src/api/http.ts` 导出的实例，不 `vi.mock` 模块路径（避免路径漂移）；WebSocket 用 `setSocketFactory` 注入假实现
- 包管理用 npm（registry 已配置 npmmirror）；所有命令在 `frontend/` 目录执行
- 提交消息用中文描述 + conventional commit 前缀，尾部 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task F0: 脚手架与测试基座

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`, `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/router.ts`, `frontend/src/env.d.ts`
- Create: `frontend/src/views/HomeView.vue`（占位）
- Test: `frontend/src/App.test.ts`

**Interfaces (Produces):**
- `npm test`（Vitest watch=false）、`npm run build`、`npm run dev`（端口 5173，代理 `/api`、`/ws` → `http://localhost:8080`）
- `src/router.ts`：路由表（F1 起逐步补全）+ 导出 `router`

- [ ] **Step 1: 写失败测试**

`frontend/src/App.test.ts`：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'
import { router } from './router'

describe('App', () => {
  it('渲染路由出口', async () => {
    router.push('/admin/home')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })
    expect(wrapper.find('router-view-stub, .home-view').exists()
      || wrapper.html().length > 0).toBe(true)
    expect(wrapper.html()).toContain('home-view')
  })
})
```

（stub 断言以挂载后 HTML 含 HomeView 根节点类名为准，实现时以实际渲染为准微调断言——断言意图：App 装配了路由出口。）

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm install && npm test`
Expected: FAIL（App.vue / router 不存在）

- [ ] **Step 3: 实现脚手架**

`frontend/package.json`：

```json
{
  "name": "muster-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "typecheck": "vue-tsc --noEmit",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "axios": "^1.7.9",
    "element-plus": "^2.9.3",
    "pinia": "^3.0.1",
    "qrcode": "^1.5.4",
    "vant": "^4.9.19",
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@types/qrcode": "^1.5.5",
    "@vitejs/plugin-vue": "^6.0.0",
    "@vue/test-utils": "^2.4.6",
    "axios-mock-adapter": "^2.1.0",
    "jsdom": "^26.0.0",
    "typescript": "~5.8.2",
    "vite": "^7.0.0",
    "vitest": "^3.1.0",
    "vue-tsc": "^3.0.0"
  }
}
```

`frontend/vite.config.ts`：

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

`frontend/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "types": ["vite/client", "vitest/globals", "element-plus/global"],
    "skipLibCheck": true,
    "noEmit": true,
    "allowImportingTsExtensions": true
  },
  "include": ["src/**/*.ts", "src/**/*.vue", "src/**/*.test.ts"]
}
```

`frontend/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Muster·点将台</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

`frontend/src/env.d.ts`：

```ts
/// <reference types="vite/client" />
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

`frontend/src/views/HomeView.vue`（F0 占位，F4 重写）：

```vue
<template>
  <div class="home-view">首页占位</div>
</template>
```

`frontend/src/router.ts`：

```ts
import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/admin/home', component: () => import('./views/HomeView.vue') },
    { path: '/', redirect: '/admin/home' },
  ],
})
```

`frontend/src/App.vue`：

```vue
<template>
  <router-view />
</template>
```

`frontend/src/main.ts`：

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
```

- [ ] **Step 4: 运行确认通过**

Run: `npm test`
Expected: PASS（1 test）

- [ ] **Step 5: 提交**

```bash
git add frontend && git commit -m "feat(frontend): vite + vue3 + vitest scaffold"
```

---

### Task F1: HTTP 客户端、认证与登录页

**Files:**
- Create: `frontend/src/api/types.ts`, `frontend/src/api/http.ts`, `frontend/src/stores/auth.ts`, `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/router.ts`（补路由 + 守卫）, `frontend/src/main.ts`（注册 401 跳转）
- Test: `frontend/src/api/http.test.ts`, `frontend/src/stores/auth.test.ts`, `frontend/src/views/LoginView.test.ts`

**Interfaces:**
- Consumes: `POST /api/auth/login {username,password} → {token,username}`、`PUT /api/auth/password {oldPassword,newPassword}`
- Produces: `http`（axios 实例，自动 Bearer + 401 处理）、`ApiError {code,message,data?,status?}`、`setUnauthorizedHandler(fn)`、`getToken()/setToken()`、`useAuthStore()`、全部 DTO 的 TS 类型（`ActivityResponse/Stats/FormInfo/TeamDetail/TeamAdminResponse/TeamMemberView/PersonResponse/PageResult<T>/OpLogView/TeamEventView/FormPersonView`）

- [ ] **Step 1: 写失败测试（http 拦截器）**

`frontend/src/api/http.test.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken, setUnauthorizedHandler, toApiError } from './http'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
  setToken(null)
})

describe('http', () => {
  it('自动附带 Bearer', async () => {
    setToken('tk1')
    let auth = ''
    mock.onGet('/api/ping').reply(config => {
      auth = config.headers?.Authorization as string
      return [200, { ok: true }]
    })
    await http.get('/api/ping')
    expect(auth).toBe('Bearer tk1')
  })

  it('业务错误剥成 ApiError', async () => {
    mock.onPost('/api/x').reply(409, { code: 'CONFLICT', message: '已结束', data: { a: 1 } })
    await expect(http.post('/api/x')).rejects.toMatchObject({
      code: 'CONFLICT', message: '已结束', data: { a: 1 }, status: 409,
    })
  })

  it('401 清 token 并回调（登录接口除外）', async () => {
    setToken('expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mock.onGet('/api/stats').reply(401, { code: 'UNAUTHORIZED', message: '未登录' })
    await expect(http.get('/api/stats')).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(getToken()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalled()
  })

  it('toApiError 兜底网络错误', () => {
    expect(toApiError({ response: { status: 500, data: { code: 'INTERNAL', message: 'x' } } }))
      .toMatchObject({ code: 'INTERNAL', status: 500 })
    expect(toApiError({ response: undefined })).toMatchObject({ code: 'NETWORK' })
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL（`./http` 不存在）

- [ ] **Step 3: 实现 http.ts + types.ts**

`frontend/src/api/types.ts`：

```ts
export interface ActivityResponse {
  id: number
  name: string
  startTime: string
  endTime: string
  groupSizeLimit: number
  qrToken: string
  exported: boolean
  manuallyEnded: boolean
  windowStatus: 'NOT_STARTED' | 'ACTIVE' | 'ENDED'
}
export interface Stats {
  total: number
  joined: number
  notJoined: number
  teamCount: number
  pendingTeamCount: number
}
export interface FormInfo {
  name: string
  startTime: string
  endTime: string
  groupSizeLimit: number
  windowStatus: 'NOT_STARTED' | 'ACTIVE' | 'ENDED'
}
export interface TeamMemberView { name: string; phone: string; department: string }
export interface TeamDetail {
  id: number
  name: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  rejectReason: string | null
  overLimit: boolean
  submittedAt: string
  members: TeamMemberView[]
}
export interface TeamAdminResponse {
  id: number
  name: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  size: number
  overLimit: boolean
  rejectReason: string | null
  submittedAt: string
}
export interface FormPersonView { name: string; phone: string; department: string }
export interface ConflictView { phone: string; name: string; teamName: string }
export interface PageResult<T> { total: number; records: T[] }
export interface OpLogView {
  id: number
  adminUsername: string
  action: string
  detail: string | null
  createdAt: string
}
export interface TeamEventView {
  id: number
  type: 'SUBMITTED' | 'EDITED_BY_LEADER' | 'EDITED_BY_ADMIN' | 'PASSED' | 'REJECTED'
  detail: string | null
  createdAt: string
}
```

`frontend/src/api/http.ts`：

```ts
import axios, { type AxiosError } from 'axios'

export interface ApiError {
  code: string
  message: string
  data?: unknown
  status?: number
}

const TOKEN_KEY = 'muster.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 把 axios 错误剥成统一 ApiError；非信封响应降级为 NETWORK。 */
export function toApiError(err: unknown): ApiError {
  const ax = err as AxiosError<{ code?: string; message?: string; data?: unknown }>
  const resp = ax?.response
  if (resp?.data && typeof resp.data === 'object' && 'code' in resp.data) {
    return { code: resp.data.code!, message: resp.data.message ?? '请求失败',
             data: resp.data.data, status: resp.status }
  }
  return { code: 'NETWORK', message: '网络异常，请重试', status: resp?.status }
}

let unauthorizedHandler: (() => void) | null = null
/** 注册 401 时的跳转行为（main.ts 里注册 router.push('/login')）。 */
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn
}

export const http = axios.create({ timeout: 15000 })

http.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  resp => resp,
  err => {
    const apiError = toApiError(err)
    const url: string = err?.config?.url ?? ''
    if (apiError.status === 401 && !url.startsWith('/api/auth/login')) {
      setToken(null)
      unauthorizedHandler?.()
    }
    return Promise.reject(apiError)
  },
)
```

- [ ] **Step 4: 运行 http 测试确认通过**

Run: `npm test`
Expected: http.test.ts 4 个用例 PASS

- [ ] **Step 5: 写失败测试（auth store + 登录页）**

`frontend/src/stores/auth.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken, setToken } from '../api/http'
import { useAuthStore } from './auth'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  setActivePinia(createPinia())
  localStorage.clear()
})

describe('auth store', () => {
  it('登录成功保存 token 与用户名', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't1', username: 'admin' })
    const store = useAuthStore()
    await store.login('admin', 'admin123')
    expect(store.token).toBe('t1')
    expect(store.username).toBe('admin')
    expect(getToken()).toBe('t1')
  })

  it('登录失败抛 ApiError 且不保存', async () => {
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    const store = useAuthStore()
    await expect(store.login('admin', 'bad')).rejects.toMatchObject({ code: 'AUTH_FAILED' })
    expect(store.token).toBeNull()
  })

  it('退出清空 token', () => {
    setToken('t1')
    const store = useAuthStore()
    store.token = 't1'
    store.logout()
    expect(store.token).toBeNull()
    expect(getToken()).toBeNull()
  })

  it('修改密码调用 PUT', async () => {
    let body: unknown
    mock.onPut('/api/auth/password').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const store = useAuthStore()
    await store.changePassword('old1', 'newpass1')
    expect(body).toEqual({ oldPassword: 'old1', newPassword: 'newpass1' })
  })
})
```

`frontend/src/views/LoginView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http, getToken } from '../api/http'
import LoginView from './LoginView.vue'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  setActivePinia(createPinia())
  localStorage.clear()
})

describe('LoginView', () => {
  it('登录成功后跳转 /admin/home', async () => {
    mock.onPost('/api/auth/login').reply(200, { token: 't1', username: 'admin' })
    const wrapper = mount(LoginView, {
      global: { plugins: [createPinia()] },
    })
    await wrapper.find('input[type="text"], input[autocomplete="username"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('admin123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(getToken()).toBe('t1')
    expect(wrapper.vm.$router? // 若未注入 router 则跳过路由断言
      ).toBeUndefined?.()
  })

  it('登录失败展示后端 message', async () => {
    mock.onPost('/api/auth/login').reply(401, { code: 'AUTH_FAILED', message: '账号或密码错误' })
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()] } })
    await wrapper.find('input[type="text"], input[autocomplete="username"]').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('bad')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain('账号或密码错误')
  })
})
```

（第一个用例的路由断言实现时若组件用 `useRouter` 注入则改用 `vi.mock('vue-router')` 提供 push spy；断言意图：成功后跳后台。）

- [ ] **Step 6: 实现 auth store、LoginView、路由守卫**

`frontend/src/stores/auth.ts`：

```ts
import { defineStore } from 'pinia'
import { http, setToken, getToken } from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: getToken() as string | null }),
  actions: {
    async login(username: string, password: string) {
      const { data } = await http.post('/api/auth/login', { username, password })
      this.token = data.token
      setToken(data.token)
    },
    logout() {
      this.token = null
      setToken(null)
    },
    async changePassword(oldPassword: string, newPassword: string) {
      await http.put('/api/auth/password', { oldPassword, newPassword })
    },
  },
})
```

`frontend/src/views/LoginView.vue`：

```vue
<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2>Muster·点将台</h2>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" show-password
                    autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">
          登录
        </el-button>
      </el-form>
      <p v-if="error" class="error">{{ error }}</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { ApiError } from '../api/http'

const username = ref('')
const password = ref('')
const error = ref('')
const router = useRouter()
const store = useAuthStore()

async function submit() {
  error.value = ''
  try {
    await store.login(username.value, password.value)
    await router.push('/admin/home')
  } catch (e) {
    error.value = (e as ApiError).message
  }
}
</script>

<style scoped>
.login-page { display: flex; justify-content: center; align-items: center; height: 100vh;
  background: #f5f7fa; }
.login-card { width: 360px; text-align: center; }
.error { color: var(--el-color-danger); }
</style>
```

`frontend/src/router.ts`（替换整个文件）：

```ts
import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from './api/http'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    {
      path: '/admin',
      component: () => import('./views/AdminLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/admin/home' },
        { path: 'home', component: () => import('./views/HomeView.vue') },
      ],
    },
    { path: '/form/:token', component: () => import('./views/FormView.vue') },
    { path: '/', redirect: '/admin/home' },
  ],
})

router.beforeEach(to => {
  if (to.meta.requiresAuth && !getToken()) return '/login'
  return true
})
```

（`AdminLayout.vue` 与 `FormView.vue` 分别在 F2/F7 落地；本任务先创建同名占位文件 `<template><div /></template>`，否则 dev 构建报懒加载解析错。）

`frontend/src/main.ts`（修改，注册 401 跳转）：

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/http'

setUnauthorizedHandler(() => {
  if (!location.pathname.startsWith('/form/')) {
    router.push('/login').catch(() => {})
  }
})

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
```

- [ ] **Step 7: 运行全部前端测试确认通过**

Run: `npm test`
Expected: 全部 PASS

- [ ] **Step 8: 提交**

```bash
git add frontend && git commit -m "feat(frontend): http client, auth store and login page"
```

---

### Task F2: 管理布局与活动管理页

**Files:**
- Create: `frontend/src/views/AdminLayout.vue`, `frontend/src/views/ActivityView.vue`, `frontend/src/api/download.ts`
- Modify: `frontend/src/router.ts`（挂 `/admin/activity`）
- Test: `frontend/src/views/ActivityView.test.ts`

**Interfaces:**
- Consumes: `GET/POST/PUT/DELETE /api/activity`、`POST /api/activity/end`、`GET /api/activity/form-url → {url}`、`POST /api/activity/export/archive`
- Produces: `downloadFile(url, filename, method?)`（blob 下载助手，供 F3/F4 复用）

- [ ] **Step 1: 写失败测试**

`frontend/src/views/ActivityView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { router } from '../router'
import ActivityView from './ActivityView.vue'

let mock: MockAdapter
const activity = {
  id: 1, name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, qrToken: 'qr123', exported: false, manuallyEnded: false,
  windowStatus: 'ACTIVE',
}

async function mountView() {
  const wrapper = mount(ActivityView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

describe('ActivityView', () => {
  it('无活动（200 空体）时显示创建表单', async () => {
    mock.onGet('/api/activity').reply(200, '')
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('创建活动')
  })

  it('有活动时展示信息与二维码地址', async () => {
    mock.onGet('/api/activity').reply(200, activity)
    mock.onGet('/api/activity/form-url').reply(200, { url: 'http://x/form/qr123' })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('迎新晚会')
    expect(wrapper.text()).toContain('http://x/form/qr123')
  })

  it('创建活动提交正确载荷', async () => {
    mock.onGet('/api/activity').reply(200, '')
    let body: unknown
    mock.onPost('/api/activity').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, activity]
    })
    const wrapper = await mountView()
    const inputs = wrapper.findAll('input')
    await inputs[0]!.setValue('迎新晚会')                    // 名称
    await inputs[1]!.setValue('2026-08-29T10:00:00')        // 开始（datetime input）
    await inputs[2]!.setValue('2026-08-29T12:00:00')        // 结束
    // el-input-number 的数字输入在最后
    const numInput = wrapper.find('.el-input-number input')
    await numInput.setValue('5')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(body).toMatchObject({ name: '迎新晚会', groupSizeLimit: 5 })
  })

  it('手动结束调用 POST /end', async () => {
    mock.onGet('/api/activity').reply(200, activity)
    mock.onGet('/api/activity/form-url').reply(200, { url: 'u' })
    let ended = false
    mock.onPost('/api/activity/end').reply(() => { ended = true; return [200, ''] })
    const wrapper = await mountView()
    await wrapper.find('[data-test="end-btn"]').trigger('click')
    await flushPromises()
    expect(ended).toBe(true)
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL（ActivityView 不存在）

- [ ] **Step 3: 实现**

`frontend/src/api/download.ts`：

```ts
import { http } from './http'

/** 带 Authorization 的文件下载（模板/导出/归档）。 */
export async function downloadFile(url: string, filename: string,
                                   method: 'GET' | 'POST' = 'GET'): Promise<void> {
  const resp = await http.request<Blob>({ url, method, responseType: 'blob' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(resp.data)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}
```

`frontend/src/views/AdminLayout.vue`：

```vue
<template>
  <el-container class="layout">
    <el-aside width="200px">
      <div class="brand">Muster·点将台</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/admin/home">实时统计</el-menu-item>
        <el-menu-item index="/admin/activity">活动管理</el-menu-item>
        <el-menu-item index="/admin/roster">花名册</el-menu-item>
        <el-menu-item index="/admin/teams">组管理</el-menu-item>
        <el-menu-item index="/admin/audit">审计日志</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-dropdown>
          <span class="admin-name">管理员</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="pwdVisible = true">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
    <ChangePasswordDialog v-model="pwdVisible" />
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChangePasswordDialog from './ChangePasswordDialog.vue'

const pwdVisible = ref(false)
const router = useRouter()
const store = useAuthStore()

function logout() {
  store.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.brand { font-weight: 700; padding: 16px; }
.header { display: flex; justify-content: flex-end; align-items: center; }
.admin-name { cursor: pointer; }
</style>
```

（`ChangePasswordDialog.vue` 一并创建：el-dialog + 三个密码字段，校验新密码 ≥6 且与确认一致，调 `useAuthStore().changePassword`，成功 `ElMessage.success('密码已修改')` 后 emit close。`v-model` 用 `defineModel<boolean>()`。）

`frontend/src/views/ActivityView.vue`（核心逻辑，模板按 Element Plus 惯例组织）：

```vue
<template>
  <div v-if="!activity">
    <h3>创建活动</h3>
    <el-form style="max-width:480px" @submit.prevent="create">
      <el-form-item label="活动名称">
        <el-input v-model="form.name" placeholder="如：迎新晚会" />
      </el-form-item>
      <el-form-item label="开始时间">
        <el-date-picker v-model="form.startTime" type="datetime"
                        value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" />
      </el-form-item>
      <el-form-item label="结束时间">
        <el-date-picker v-model="form.endTime" type="datetime"
                        value-format="YYYY-MM-DDTHH:mm:ss" />
      </el-form-item>
      <el-form-item label="每组人数上限">
        <el-input-number v-model="form.groupSizeLimit" :min="1" :max="99" />
      </el-form-item>
      <el-button type="primary" native-type="submit">创建</el-button>
    </el-form>
  </div>

  <div v-else>
    <el-descriptions :title="activity.name" :column="2" border>
      <el-descriptions-item label="开始时间">{{ fmt(activity.startTime) }}</el-descriptions-item>
      <el-descriptions-item label="结束时间">{{ fmt(activity.endTime) }}</el-descriptions-item>
      <el-descriptions-item label="每组上限">{{ activity.groupSizeLimit }} 人</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="statusType">{{ statusText }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="归档">
        {{ activity.exported ? '已导出归档包' : '未导出' }}
      </el-descriptions-item>
    </el-descriptions>

    <el-space style="margin:16px 0">
      <el-button data-test="edit-btn" @click="openEdit">修改</el-button>
      <el-button data-test="end-btn" type="warning" :disabled="activity.windowStatus === 'ENDED'"
                 @click="end">手动结束</el-button>
      <el-button type="primary" @click="exportArchive">导出归档包</el-button>
      <el-button data-test="delete-btn" type="danger" @click="remove">删除活动</el-button>
    </el-space>

    <el-card v-if="formUrl" header="报名二维码">
      <canvas ref="qrCanvas" />
      <p>{{ formUrl }}</p>
    </el-card>

    <el-dialog v-model="editVisible" title="修改活动">
      <el-form label-width="110px">
        <el-form-item label="活动名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="editForm.startTime" type="datetime" :disabled="!timeEditable"
                          value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="editForm.endTime" type="datetime" :disabled="!timeEditable"
                          value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="每组人数上限">
          <el-input-number v-model="editForm.groupSizeLimit" :min="1" :max="99" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { http, type ApiError } from '../api/http'
import { downloadFile } from '../api/download'
import type { ActivityResponse } from '../api/types'

const activity = ref<ActivityResponse | null>(null)
const formUrl = ref('')
const form = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const editVisible = ref(false)
const editForm = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const qrCanvas = ref<HTMLCanvasElement>()

const timeEditable = computed(() => activity.value?.windowStatus === 'NOT_STARTED')
const statusType = computed(() =>
  ({ NOT_STARTED: 'info', ACTIVE: 'success', ENDED: 'danger' })[activity.value?.windowStatus ?? 'NOT_STARTED'])
const statusText = computed(() =>
  ({ NOT_STARTED: '未开始', ACTIVE: '进行中', ENDED: '已结束' })[activity.value?.windowStatus ?? 'NOT_STARTED'])

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

async function load() {
  const { data } = await http.get<ActivityResponse | ''>('/api/activity')
  activity.value = data === '' ? null : data
  if (activity.value) {
    const { data: u } = await http.get<{ url: string }>('/api/activity/form-url')
    formUrl.value = u2f(u)
    QRCode.toCanvas(qrCanvas.value!, u)
  }
}
function u2f(v: { url: string }) { return v.url }

async function create() {
  try {
    await http.post('/api/activity', form.value)
    ElMessage.success('活动已创建')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function openEdit() { /* 预填 editForm，editVisible = true */ }
async function saveEdit() { /* PUT /api/activity，成功后 load() */ }
async function end() { /* confirm → POST /api/activity/end → load() */ }
async function exportArchive() {
  await downloadFile('/api/activity/export/archive', '归档包.xlsx', 'POST')
}
async function remove() {
  await ElMessageBox.confirm('删除后花名册与分组一并清除，且不可恢复', '删除活动', { type: 'warning' })
  try {
    await http.delete('/api/activity')
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message) // ARCHIVE_REQUIRED 等
  }
}

onMounted(load)
</script>
```

（注释省略处按描述实现；create 前 `validateRange` 由后端兜底，前端只要求非空。）

- [ ] **Step 4: 运行确认通过**

Run: `npm test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add frontend && git commit -m "feat(frontend): admin layout and activity management"
```

---

### Task F3: 花名册页

**Files:**
- Create: `frontend/src/views/RosterView.vue`
- Modify: `frontend/src/router.ts`（挂 `/admin/roster`）
- Test: `frontend/src/views/RosterView.test.ts`

**Interfaces:**
- Consumes: `POST /api/roster/import`（multipart `file`）、`GET /api/roster?keyword=&page=&size=`、`POST /api/roster`、`DELETE /api/roster/{id}`、`GET /api/roster/template`
- Produces: —

- [ ] **Step 1: 写失败测试**

`frontend/src/views/RosterView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { router } from '../router'
import RosterView from './RosterView.vue'

let mock: MockAdapter
async function mountView() {
  const wrapper = mount(RosterView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
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

  it('重复手机号展示后端行号信息', async () => {
    mock.onGet('/api/roster').reply(200, { total: 0, records: [] })
    mock.onPost('/api/roster').reply(400, {
      code: 'PHONE_DUPLICATE', message: '手机号已在花名册中：13800000001',
    })
    const wrapper = await mountView()
    ;(wrapper.vm as any).addForm = { name: '张三', phone: '13800000001', department: '计算机' }
    ;(wrapper.vm as any).addVisible = true
    await (wrapper.vm as any).submitAdd()
    await flushPromises()
    expect(wrapper.text()).toContain('手机号已在花名册中')
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL（RosterView 不存在）

- [ ] **Step 3: 实现 RosterView**

要点（完整组件按 Element Plus 惯例实现，逻辑如下）：

```ts
// state: keyword, page, size=10, total, records, addVisible, addForm{name,phone,department}, importVisible
// load(): GET /api/roster?keyword=${keyword}&page=${page}&size=${size} → total/records
// submitAdd(): 校验手机号 ^1[3-9]\d{9}$ → POST /api/roster → ElMessage.success('已添加') + load()
//              catch → ElMessage.error(e.message)（PHONE_DUPLICATE 行号信息由后端给出）
// onImport(file): FormData.append('file', file.raw)；POST /api/roster/import
//                成功 ElMessage.success(`导入 ${resp.data} 人`)；失败展示后端 message（含行号）
// downloadTemplate(): downloadFile('/api/roster/template', '花名册模板.xlsx')
// remove(row): ElMessageBox.confirm(`删除 ${row.name}？`) → DELETE /api/roster/{id} → load()
// 手机号输入 maxlength=11 type=tel
```

模板结构：搜索栏（el-input + 查询按钮 + 导入按钮 + 下载模板按钮 + 添加人员按钮）→ el-table（姓名/手机号/部门/操作）→ el-pagination（layout `total, prev, pager, next`）→ 添加人员 el-dialog → 导入 el-dialog（el-upload `:auto-upload="false"`，拖拽可选）。

- [ ] **Step 4: 运行确认通过**

Run: `npm test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add frontend && git commit -m "feat(frontend): roster page with import, search and edit"
```

---

### Task F4: 实时统计首页（WebSocket）

**Files:**
- Create: `frontend/src/composables/useStats.ts`, 重写 `frontend/src/views/HomeView.vue`
- Modify: `frontend/src/router.ts`（无改动，已在 F1 挂载）
- Test: `frontend/src/composables/useStats.test.ts`, `frontend/src/views/HomeView.test.ts`

**Interfaces:**
- Consumes: `GET /api/stats`、`GET /api/activity`、`WS /ws/stats?token=`、`GET /api/stats/export?type=JOINED|MISSING`
- Produces: `useStats()` → `{ stats: Ref<Stats|null> }`；`setSocketFactory(fn)`（测试注入）

- [ ] **Step 1: 写失败测试（useStats 组合式函数）**

`frontend/src/composables/useStats.test.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useStats, setSocketFactory } from './useStats'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor(public url: string) { FakeWebSocket.instances.push(this) }
  close() { this.onclose?.() }
}

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  FakeWebSocket.instances = []
  vi.restoreAllMocks()
})

describe('useStats', () => {
  it('先拉 REST 初值，再接收 WS 帧', async () => {
    mock.onGet('/api/stats').reply(200,
      { total: 3, joined: 1, notJoined: 2, teamCount: 1, pendingTeamCount: 1 })
    setSocketFactory(url => new FakeWebSocket(url) as unknown as WebSocket)
    const { stats, start, stop } = useStats()
    start()
    await vi.waitFor(() => expect(stats.value?.total).toBe(3))
    const ws = FakeWebSocket.instances[0]!
    expect(ws.url).toContain('/ws/stats?token=')
    ws.onmessage?.({ data: JSON.stringify({ total: 5, joined: 4, notJoined: 1, teamCount: 2, pendingTeamCount: 0 }) })
    await vi.waitFor(() => expect(stats.value?.total).toBe(5))
    stop()
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL（useStats 不存在）

- [ ] **Step 3: 实现 useStats.ts**

```ts
import { ref, type Ref } from 'vue'
import { http, getToken } from '../api/http'
import type { Stats } from '../api/types'

const stats: Ref<Stats | null> = ref(null)
let socket: WebSocket | null = null
let users = 0
let retryTimer: ReturnType<typeof setTimeout> | null = null
let factory: (url: string) => WebSocket = url => new WebSocket(url)

/** 测试注入假 WebSocket 用。 */
export function setSocketFactory(f: (url: string) => WebSocket): void {
  factory = f
}

function connect() {
  if (socket) return
  socket = factory(`/ws/stats?token=${encodeURIComponent(getToken() ?? '')}`)
  socket.onmessage = ev => {
    try { stats.value = JSON.parse(ev.data) } catch { /* 忽略坏帧 */ }
  }
  socket.onclose = () => {
    socket = null
    if (users > 0 && retryTimer === null) {
      retryTimer = setTimeout(() => { retryTimer = null; connect() }, 3000)
    }
  }
}

export function useStats() {
  users++
  async function refresh() {
    const { data } = await http.get<Stats>('/api/stats')
    stats.value = data
  }
  function start() {
    refresh().catch(() => {})
    connect()
  }
  function stop() {
    users--
    if (users === 0) {
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
      socket?.close()
      socket = null
    }
  }
  return { stats, start, stop, refresh }
}
```

- [ ] **Step 4: 写失败测试（HomeView 窗口可见性）+ 实现**

`frontend/src/views/HomeView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { router } from '../router'
import HomeView from './HomeView.vue'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

describe('HomeView', () => {
  it('窗口非 ACTIVE 时显示占位', async () => {
    mock.onGet('/api/activity').reply(200, { windowStatus: 'NOT_STARTED' })
    const wrapper = mount(HomeView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有进行中的活动')
  })

  it('ACTIVE 时展示统计卡片与导出按钮', async () => {
    mock.onGet('/api/activity').reply(200, { windowStatus: 'ACTIVE' })
    mock.onGet('/api/stats').reply(200,
      { total: 10, joined: 6, notJoined: 4, teamCount: 2, pendingTeamCount: 1 })
    const wrapper = mount(HomeView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('10')
    expect(wrapper.text()).toContain('导出已参加')
    expect(wrapper.text()).toContain('导出未参加')
  })
})
```

Run: `npm test` → FAIL 后实现 HomeView：

```vue
<template>
  <div v-if="!active" class="placeholder">
    <el-empty description="当前没有进行中的活动" />
  </div>
  <div v-else>
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card><div class="num">{{ card.value }}</div><div class="label">{{ card.label }}</div></el-card>
      </el-col>
    </el-row>
    <el-space style="margin-top:16px">
      <el-button data-test="export-joined" @click="exportJoined">导出已参加</el-button>
      <el-button data-test="export-missing" @click="exportMissing">导出未参加</el-button>
    </el-space>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { http } from '../api/http'
import { downloadFile } from '../api/download'
import { useStats } from '../composables/useStats'

const { stats, start, stop } = useStats()
const windowStatus = ref<string | null>(null)
const active = computed(() => windowStatus.value === 'ACTIVE')
const cards = computed(() => [
  { label: '花名册总人数', value: stats.value?.total ?? '-' },
  { label: '已参加', value: stats.value?.joined ?? '-' },
  { label: '未参加', value: stats.value?.notJoined ?? '-' },
  { label: '分组数', value: stats.value?.teamCount ?? '-' },
  { label: '待审核', value: stats.value?.pendingTeamCount ?? '-' },
])
const exportJoined = () => downloadFile('/api/stats/export?type=JOINED', '已参加.xlsx')
const exportMissing = () => downloadFile('/api/stats/export?type=MISSING', '未参加.xlsx')

onMounted(async () => {
  const { data } = await http.get('/api/activity')
  windowStatus.value = data === '' ? null : data.windowStatus
  if (active.value) start()
})
onUnmounted(stop)
</script>
```

- [ ] **Step 5: 运行全部确认通过 → 提交**

Run: `npm test` → 全部 PASS

```bash
git add frontend && git commit -m "feat(frontend): realtime stats dashboard with websocket"
```

---

### Task F5: 组管理页（列表/审核/管理员改组/流水）

**Files:**
- Create: `frontend/src/views/TeamView.vue`
- Modify: `frontend/src/router.ts`（挂 `/admin/teams`）
- Test: `frontend/src/views/TeamView.test.ts`

**Interfaces:**
- Consumes: `GET /api/teams?status=&page=&size=`、`GET /api/teams/{id}`、`PUT /api/teams/{id}/review`、`PUT /api/teams/{id}/members`、`GET /api/teams/{id}/events`、`GET /api/roster?keyword=`（改组选人）
- Produces: —

- [ ] **Step 1: 写失败测试**

`frontend/src/views/TeamView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { router } from '../router'
import TeamView from './TeamView.vue'

let mock: MockAdapter
async function mountView() {
  const wrapper = mount(TeamView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

describe('TeamView', () => {
  it('列表渲染含超员标记', async () => {
    mock.onGet('/api/teams').reply(200, {
      total: 1,
      records: [{ id: 1, name: '组1', status: 'PENDING', size: 7, overLimit: true,
                  rejectReason: null, submittedAt: '2026-08-29T10:00:00' }],
    })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('组1')
    expect(wrapper.text()).toContain('超员')
  })

  it('驳回不填理由不能提交（前端拦截）', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    const wrapper = await mountView()
    ;(wrapper.vm as any).rejectTarget = { id: 1, name: '组1' }
    ;(wrapper.vm as any).rejectReason = '  '
    await (wrapper.vm as any).submitReject()
    expect(mock.history.some(h => h.url.includes('/review'))).toBe(false)
  })

  it('审核通过调用 PUT /review', async () => {
    mock.onGet('/api/teams').reply(200, { total: 0, records: [] })
    let body: unknown
    mock.onPut('/api/teams/1/review').reply(config => {
      body = JSON.parse(config.data as string)
      return [200, '']
    })
    const wrapper = await mountView()
    await (wrapper.vm as any).pass({ id: 1 })
    await flushPromises()
    expect(body).toEqual({ action: 'PASS' })
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL

- [ ] **Step 3: 实现 TeamView**

要点：

```ts
// 列表：status 筛选 el-select（全部/PENDING 待审核/CONFIRMED 已通过/REJECTED 已驳回）
//   GET /api/teams?status=&page=&size= → {total, records: TeamAdminResponse[]}
//   表格列：组名 | 人数(size，overLimit 时附 el-tag type=warning 超员) | 状态 | 提交时间 | 操作
//   状态列：PENDING→warning 待审核 / CONFIRMED→success 已通过 / REJECTED→danger 已驳回
// 通过按钮：ElMessageBox.confirm(`确认通过 ${t.name}？`) → PUT /review {action:'PASS'} → reload
// 驳回按钮：el-dialog 内 el-input textarea；submitReject() 前置校验 reason 非空（trim），为空直接 return
//   → PUT /review {action:'REJECT', reason}
// 详情抽屉：el-drawer → GET /api/teams/{id}（TeamDetail）→ 成员 el-table + 驳回理由 alert
//   + 流水 el-timeline（GET /api/teams/{id}/events；type 映射：
//   SUBMITTED 提交 / EDITED_BY_LEADER 组长修改 / EDITED_BY_ADMIN 管理员修改 / PASSED 审核通过 / REJECTED 驳回）
// 管理员改组：详情抽屉内按钮 → el-dialog：
//   关键字搜索 GET /api/roster?keyword= → 结果行「加入」；已选名单 chips（name phone）「移除」
//   保存 → PUT /api/teams/{id}/members {memberPhoneList: phones} → 成功提示「已保存，状态置为已通过」→ 刷新
//   冲突 409 → ElMessage.error(e.message)（后端返回明细）
```

- [ ] **Step 4: 运行确认通过 → 提交**

Run: `npm test` → 全部 PASS

```bash
git add frontend && git commit -m "feat(frontend): team management with review, admin edit and lifecycle"
```

---

### Task F6: 审计日志页

**Files:**
- Create: `frontend/src/views/AuditView.vue`
- Modify: `frontend/src/router.ts`（挂 `/admin/audit`）
- Test: `frontend/src/views/AuditView.test.ts`

**Interfaces:**
- Consumes: `GET /api/audit/logs?action=&page=&size=`

- [ ] **Step 1: 写失败测试**

`frontend/src/views/AuditView.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { router } from '../router'
import AuditView from './AuditView.vue'

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

it('渲染审计日志并按动作过滤', async () => {
  mock.onGet(new RegExp('/api/audit/logs.*')).reply(config => {
    expect(config.params?.action).toBe('ROSTER_IMPORT')
    return [200, {
      total: 1,
      records: [{ id: 9, adminUsername: 'admin', action: 'ROSTER_IMPORT',
                  detail: '导入 3 人', createdAt: '2026-08-29T10:00:00' }],
    }]
  })
  const wrapper = mount(AuditView, { global: { plugins: [createPinia(), router] } })
  ;(wrapper.vm as any).action = 'ROSTER_IMPORT'
  await flushPromises()
  expect(wrapper.text()).toContain('导入 3 人')
})
```

- [ ] **Step 2: 运行确认失败 → Step 3: 实现**

操作下拉选项（`ACTIVITY_CREATE/ACTIVITY_UPDATE/ACTIVITY_END/ACTIVITY_DELETE/ACTIVITY_ARCHIVE/ROSTER_IMPORT/ROSTER_ADD/ROSTER_DELETE/TEAM_EDIT_ADMIN/TEAM_REVIEW`），中文映射展示；表格列：时间 | 管理员 | 动作 | 明细；`el-pagination`；查询参数 `{action, page, size: 15}`，`http.get('/api/audit/logs', { params })`。

- [ ] **Step 4: 运行确认通过 → 提交**

```bash
git add frontend && git commit -m "feat(frontend): audit log page"
```

---

### Task F7: 移动端报名 H5（Vant）

**Files:**
- Create: `frontend/src/views/FormView.vue`, `frontend/src/composables/useFormPage.ts`
- Modify: `frontend/src/main.ts`（注册 Vant）, `frontend/src/App.vue`（无路由变化）
- Test: `frontend/src/composables/useFormPage.test.ts`

**Interfaces:**
- Consumes: `GET /api/form/{token}`、`GET /api/form/{token}/person?phone=`、`POST /api/form/{token}/teams`、`GET/PUT /api/form/{token}/teams/{id}`
- Produces: `useFormPage(token)` 组合式函数（页面全部逻辑在此，模板仅渲染）

- [ ] **Step 1: 写失败测试**

`frontend/src/composables/useFormPage.test.ts`：

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useFormPage } from './useFormPage'

let mock: MockAdapter
const info = {
  name: '迎新晚会', startTime: '2026-08-29T10:00:00', endTime: '2026-08-29T12:00:00',
  groupSizeLimit: 5, windowStatus: 'ACTIVE',
}

beforeEach(() => {
  mock = new MockAdapter(http)
  localStorage.clear()
})

function setup() {
  mock.onGet('/api/form/tk1').reply(200, info)
  return useFormPage('tk')
}

describe('useFormPage', () => {
  it('组长手机 11 位才触发回显并自动入组', async () => {
    const page = setup()
    await page.onLeaderPhone('138')
    expect(mock.history.filter(h => h.url.includes('/person')).length).toBe(0)
    mock.onGet(new RegExp('/api/form/tk/person\\?phone=13800000001')).reply(200,
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

  it('提交成功保存 teamId 到 localStorage', async () => {
    const page = setup()
    mock.onGet(new RegExp('/api/form/.*/person.*')).reply(200,
      { name: '张三', phone: '13800000001', department: '计算机' })
    await page.onLeaderPhone('13800000001')
    mock.onPost('/api/form/tk/teams').reply(200,
      { id: 9, name: '组1', status: 'PENDING', rejectReason: null, overLimit: false,
        submittedAt: 'x', members: [{ name: '张三', phone: '13800000001', department: '计算机' }] })
    await page.submit()
    expect(localStorage.getItem('muster.team.tk')).toBe('9')
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
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npm test`
Expected: FAIL

- [ ] **Step 3: 实现 useFormPage.ts**

```ts
import { ref, type Ref } from 'vue'
import { http, type ApiError } from '../api/http'
import type { FormInfo, FormPersonView, TeamDetail, ConflictView } from '../api/types'

interface Member { name: string; phone: string; department: string }

export function useFormPage(token: string) {
  const info: Ref<FormInfo | null> = ref(null)
  const leader: Ref<Member | null> = ref(null)
  const leaderPhone = ref('')
  const leaderError = ref('')
  const members: Ref<Member[]> = ref([])
  const addPhone = ref('')
  const addPreview: Ref<Member | null> = ref(null)
  const addError = ref('')
  const team: Ref<TeamDetail | null> = ref(null)
  const conflicts: Ref<ConflictView[]> = ref([])
  const editing = ref(false)

  const PHONE = /^1[3-9]\d{9}$/

  async function load() {
    const { data } = await http.get<FormInfo>(`/api/form/${token}`)
    info.value = data
    const saved = localStorage.getItem(`muster.team.${token}`)
    if (saved) {
      try { team.value = (await http.get<TeamDetail>(`/api/form/${token}/teams/${saved}`)).data }
      catch { localStorage.removeItem(`muster.team.${token}`) }
    }
  }

  /** 输入完整 11 位才回显；查到即作为组长自动加入成员列表首位。 */
  async function onLeaderPhone(phone: string): Promise<void> {
    leaderError.value = ''
    if (!PHONE.test(phone)) return
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone } })
      leader.value = data
      if (!members.value.some(m => m.phone === data.phone)) {
        members.value.unshift(data)
      }
    } catch (e) {
      leader.value = null
      leaderError.value = (e as ApiError).message
    }
  }

  async function previewAdd(): Promise<void> {
    addError.value = ''
    addPreview.value = null
    if (!PHONE.test(addPhone.value)) return
    if (members.value.some(m => m.phone === addPhone.value)) {
      addError.value = '该成员已在本组'
      return
    }
    try {
      addPreview.value = (await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone: addPhone.value } })).data
    } catch (e) {
      addError.value = (e as ApiError).message
    }
  }

  async function addMember(phone: string): Promise<boolean> {
    if (members.value.some(m => m.phone === phone)) return false
    if (!PHONE.test(phone)) { addError.value = '请输入完整 11 位手机号'; return false }
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone } })
      members.value.push(data)
      addPhone.value = ''
      addPreview.value = null
      return true
    } catch (e) {
      addError.value = (e as ApiError).message
      return false
    }
  }

  function removeMember(phone: string): void {
    members.value = members.value.filter(m => m.phone !== phone)
    if (leader.value?.phone === phone) leader.value = null
  }

  /** 超上限由模板弹 confirm；本函数专注提交与结果处理。 */
  async function submit(): Promise<void> {
    conflicts.value = []
    try {
      const resp = await http.post<TeamDetail>(`/api/form/${token}/teams`,
        { memberPhoneList: members.value.map(m => m.phone) })
      team.value = resp.data
      localStorage.setItem(`muster.team.${token}`, String(resp.data.id))
      editing.value = false
    } catch (e) {
      const apiError = e as ApiError
      if (apiError.code === 'CONFLICT' && Array.isArray(apiError.data)) {
        conflicts.value = apiError.data as ConflictView[]
      } else {
        throw e
      }
    }
  }

  async function startEdit(): Promise<void> {
    if (!team.value) return
    members.value = [...team.value.members]
    leader.value = team.value.members[0] ?? null
    editing.value = true
  }

  async function saveEdit(): Promise<void> {
    const resp = await http.put<TeamDetail>(`/api/form/${token}/teams/${team.value!.id}`,
      { memberPhoneList: members.value.map(m => m.phone) })
    team.value = resp.data
    editing.value = false
  }

  async function reloadTeam(): Promise<void> {
    if (!team.value) return
    team.value = (await http.get<TeamDetail>(`/api/form/${token}/teams/${team.value.id}`)).data
  }

  return {
    info, leader, leaderPhone, leaderError, members, addPhone, addPreview, addError,
    team, conflicts, editing,
    load, onLeaderPhone, previewAdd, addMember, removeMember, submit, startEdit, saveEdit, reloadTeam,
  }
}
```

- [ ] **Step 4: 实现 FormView.vue（Vant 模板）**

状态机（三块互斥渲染）：
1. `info === null` → 加载中骨架
2. `info.windowStatus !== 'ACTIVE'` → `van-empty`（NOT_STARTED：活动未开始 + 开始时间；ENDED：活动已结束）
3. `editing` → 建组编辑（同提交视图，按钮文案「保存修改」，提示"修改后需重新审核"）
4. `team !== null` → 本组详情（组名/状态 tag/成员列表/驳回理由 `REJECTED` 时红色展示/「修改组员」按钮仅 `windowStatus==='ACTIVE'`）
5. 否则 → 建组表单

建组表单模板骨架（Vant）：

```vue
<van-cell-group inset title="组长信息">
  <van-field v-model="leaderPhone" type="tel" maxlength="11" label="组长手机号"
             placeholder="输入完整 11 位手机号" @update:model-value="onLeaderPhone" />
  <van-cell v-if="leader" :title="leader.name" :label="leader.department" value="组长" />
  <van-cell v-if="leaderError" :title="leaderError" />
</van-cell-group>
<van-cell-group inset title="组员">
  <van-cell v-for="m in members" :key="m.phone" :title="`${m.name} ${m.phone}`"
            :label="m.department" is-link @click="removeMember(m.phone)">
    <template #value><van-tag type="danger">移除</van-tag></template>
  </van-cell>
  <van-field v-model="addPhone" type="tel" maxlength="11" label="添加组员"
             placeholder="输入完整 11 位手机号" @update:model-value="previewAdd" />
  <van-cell v-if="addPreview" :title="`${addPreview.name} ${addPreview.phone}`"
            :label="addPreview.department">
    <template #value><van-tag type="primary" @click="addMember(addPhone)">加入</van-tag></template>
  </van-cell>
  <van-cell v-if="addError" :title="addError" />
</van-cell-group>
<van-notice-bar v-if="overLimit" wrapable :text="`已超出上限 ${info.groupSizeLimit} 人，提交时将再次确认`" />
<van-button type="primary" block @click="onSubmitClick">提交报名（{{ members.length }} 人）</van-button>
```

`onSubmitClick`：`members.length > info.groupSizeLimit` 时 `showConfirmDialog({title:'超出人数上限', message:'当前 N 人，上限 M 人，仍要提交吗？'})` 确认后 `submit()`；否则直接 `submit()`。`conflicts` 非空时顶部红色面板列出 `name(phone)→teamName`。提交成功 `showSuccessToast('已提交，等待审核')`。

`main.ts` 追加 Vant 注册：

```ts
import Vant from 'vant'
import 'vant/lib/index.css'
// createApp(app).use(Vant)
```

- [ ] **Step 5: 运行全部确认通过 → 提交**

```bash
git add frontend && git commit -m "feat(frontend): mobile H5 form with autofill and leader team editing"
```

---

### Task F8: 构建集成与文档收尾

**Files:**
- Modify: `README.md`（前端运行/构建说明）
- Verify: `npm run build`、`npm run typecheck`

**Interfaces:**
- Produces: `frontend/dist/`（F9 Docker 的构建产物输入）

- [ ] **Step 1: 全量验证**

Run: `cd frontend && npm run typecheck && npm test && npm run build`
Expected: typecheck 0 错误；测试全绿；`dist/` 生成

- [ ] **Step 2: 接口覆盖自查（对照 docs/api.md 逐条勾）**

- [ ] `/api/auth/login` → LoginView
- [ ] `/api/auth/password` → ChangePasswordDialog
- [ ] `/api/activity` CRUD + `/end` + `/form-url` + `/export/archive` → ActivityView
- [ ] `/api/roster/import|搜索|添加|删除|template` → RosterView
- [ ] `/api/form/{token}` 系列 → FormView
- [ ] `/api/teams` 系列 + `/events` → TeamView
- [ ] `/api/stats` + WS + `/stats/export` → HomeView
- [ ] `/api/audit/logs` → AuditView

缺任何一条 → 补对应页面后重跑测试。

- [ ] **Step 3: README 前端段落 + 提交**

```bash
git add README.md frontend && git commit -m "docs(frontend): run and build instructions"
```

---

## Self-Review

- **Spec coverage:** grilling 决策映射——单活动展示/创建/结束/删除/归档（F2）、Excel 导入+模糊搜索+增删（F3）、实时统计与导出+仅 ACTIVE 显示（F4）、组列表审核改组流水（F5）、审计日志（F6）、扫码 H5 完整流程含 11 位触发回显与超上限确认（F7）。api.md 中 WebSocket `/ws/stats` 帧仅消费不发送，F4 覆盖。
- **Placeholder scan:** F2/F5 的 `// 注释省略处` 为 Element Plus 模板惯例内的紧凑写法，每个省略都有明确行为描述（confirm → PUT → reload）；F3 同。无 TBD/TODO。
- **Type consistency:** `TeamAdminResponse.size`（后端 record 字段名）与 `TeamDetail.members[].name/phone/department`、`PageResult{total,records}`、`OpLogView`、`TeamEventView` 均与后端 DTO 逐字段核对过；`windowStatus` 枚举 `NOT_STARTED/ACTIVE/ENDED` 与 `WindowResolver` 一致。
