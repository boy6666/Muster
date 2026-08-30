<template>
  <div class="login-wrap">
    <div class="login-hero">
      <svg class="deco" viewBox="0 0 100 100" fill="none">
        <polygon points="50,4 92,27 92,73 50,96 8,73 8,27" stroke="#0891b2" stroke-width="1.4" opacity=".7"/>
        <polygon points="50,18 80,34 80,66 50,82 20,66 20,34" stroke="#7c3aed" stroke-width="1" opacity=".5"/>
        <circle cx="50" cy="50" r="5" fill="#06b6d4"/>
        <circle cx="50" cy="50" r="14" stroke="#0891b2" stroke-width="1" opacity=".5"/>
      </svg>
      <h1>MUSTER <em>· 点将台</em></h1>
      <p class="sub">公司内部活动分组报名系统 · 实时统计 / 智能分组 / 一键归档</p>
      <div class="meta">
        <div><b>✓</b>实时统计</div>
        <div><b>✓</b>智能分组</div>
        <div><b>✓</b>一键归档</div>
      </div>
    </div>
    <div class="login-card-col">
      <form class="panel corner login-card" @submit.prevent="submit">
        <h2>管理员登录</h2>
        <p class="hint">ADMIN CONSOLE ACCESS</p>
        <div v-if="error" class="alert err">{{ error }}</div>
        <label class="f-label" for="login-username">用户名 / USERNAME</label>
        <input id="login-username" v-model="username" class="input" autocomplete="username"
               placeholder="用户名" />
        <label class="f-label" for="login-password">密码 / PASSWORD</label>
        <input id="login-password" v-model="password" class="input" type="password"
               autocomplete="current-password" placeholder="密码" />
        <button class="btn primary" type="submit" :disabled="loading">接 入 控 制 台</button>
      </form>
    </div>
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
const loading = ref(false)
const router = useRouter()
const store = useAuthStore()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await store.login(username.value, password.value)
    await router.push('/admin/home')
  } catch (e) {
    error.value = (e as ApiError).message
  } finally {
    loading.value = false
  }
}
</script>
