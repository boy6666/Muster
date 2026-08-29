<template>
  <el-dialog v-model="visible" title="修改密码" width="400px" @closed="reset">
    <el-form label-width="90px">
      <el-form-item label="原密码">
        <el-input v-model="oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="newPassword" type="password" show-password
                  autocomplete="new-password" placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input v-model="confirmPassword" type="password" show-password
                  autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import type { ApiError } from '../api/http'

const visible = defineModel<boolean>({ default: false })
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const saving = ref(false)
const store = useAuthStore()

function reset() {
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
}

async function save() {
  if (newPassword.value.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  saving.value = true
  try {
    await store.changePassword(oldPassword.value, newPassword.value)
    ElMessage.success('密码已修改')
    visible.value = false
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    saving.value = false
  }
}
</script>
