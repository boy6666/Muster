<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <svg class="logo" viewBox="0 0 100 100" fill="none">
          <polygon points="50,4 92,27 92,73 50,96 8,73 8,27" stroke="#0891b2" stroke-width="5"/>
          <circle cx="50" cy="50" r="12" fill="#06b6d4"/>
        </svg>
        <div><b>点将台</b><small>MUSTER CONSOLE</small></div>
      </div>
      <nav class="nav">
        <router-link to="/admin/home" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/></svg>
          实时统计
        </router-link>
        <router-link to="/admin/activity" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 21V4M5 4h13l-2.5 4L18 12H5"/></svg>
          活动管理
        </router-link>
        <router-link to="/admin/roster" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.5 3.4-5.5 6.5-5.5s5.7 2 6.5 5.5M16 4.6a3.5 3.5 0 010 6.8M21.5 20c-.5-2.4-2-4.1-4-4.9"/></svg>
          花名册
        </router-link>
        <router-link to="/admin/teams" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l8 3.5v5c0 5-3.4 9.4-8 11-4.6-1.6-8-6-8-11v-5L12 2z"/></svg>
          组管理
        </router-link>
        <router-link to="/admin/audit" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 5h16M4 12h16M4 19h10"/><circle cx="19" cy="19" r="1.6" fill="currentColor"/></svg>
          审计日志
        </router-link>
      </nav>
      <div class="side-foot">
        <span class="mono">SYSTEM v1.0</span> · 单活动模式 <span class="ready">READY</span>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <span class="crumb">{{ crumb }}</span>
        <div class="top-right">
          <span class="live-badge"><span class="live-dot"></span>LIVE</span>
          <span class="clock mono">{{ clock }}</span>
          <div class="admin-chip" @click="menuOpen = !menuOpen">
            <span class="avatar">A</span><span>admin</span>
          </div>
          <div v-if="menuOpen" class="admin-menu">
            <div class="admin-menu-item" @click="openPassword">修改密码</div>
            <div class="admin-menu-item out" @click="logout">退出登录</div>
          </div>
        </div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
    <ChangePasswordDialog v-model="pwdVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChangePasswordDialog from './ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useAuthStore()

const crumb = computed(() => String(route.meta.title ?? ''))
const clock = ref(fmtNow())
const menuOpen = ref(false)
const pwdVisible = ref(false)

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    clock.value = fmtNow()
  }, 1000)
  document.addEventListener('click', onDocClick)
})
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('click', onDocClick)
})

/** 管理员菜单点外部收起;chip 与菜单项自身都在 .top-right 内,不触发 */
function onDocClick(e: MouseEvent) {
  if (menuOpen.value && !(e.target as HTMLElement).closest?.('.top-right')) {
    menuOpen.value = false
  }
}

function fmtNow() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function openPassword() {
  menuOpen.value = false
  pwdVisible.value = true
}

function logout() {
  store.logout()
  router.push('/login')
}
</script>

<style scoped>
/* tokens.css 无下拉菜单类,这里只补弹层定位,视觉令牌仍取自全局变量 */
.top-right { position: relative; }
.admin-menu { position: absolute; right: 0; top: 46px; min-width: 140px; z-index: 30;
  background: var(--bg-1); border: 1px solid var(--line-strong); border-radius: 10px;
  box-shadow: 0 14px 34px rgba(13,90,132,.18); padding: 6px; }
.admin-menu-item { padding: 9px 14px; border-radius: 8px; cursor: pointer; font-size: 13px;
  color: var(--text-1); white-space: nowrap; }
.admin-menu-item:hover { background: rgba(6,182,212,.08); color: var(--cyan); }
.admin-menu-item.out { color: var(--red); }
.admin-menu-item.out:hover { background: rgba(225,29,72,.07); color: var(--red); }
.ready { color: var(--green); }
</style>
