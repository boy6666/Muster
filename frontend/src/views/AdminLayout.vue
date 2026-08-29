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
