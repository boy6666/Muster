<template>
  <UiModal v-model:visible="visible" title="修改密码" width="420px">
    <label class="f-label" for="pwd-old">原密码</label>
    <input id="pwd-old" v-model="oldPassword" class="input" type="password" style="width:100%"
           autocomplete="current-password" placeholder="输入当前密码" />
    <label class="f-label" for="pwd-new">新密码</label>
    <input id="pwd-new" v-model="newPassword" class="input" type="password" style="width:100%"
           autocomplete="new-password" placeholder="至少 6 位" />
    <template #footer>
      <button class="btn ghost" @click="visible = false">取消</button>
      <button class="btn primary" :disabled="saving" @click="save">保存</button>
    </template>
  </UiModal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import UiModal from '../components/ui/UiModal.vue'
import { toast } from '../components/ui/toast'
import { useAuthStore } from '../stores/auth'
import type { ApiError } from '../api/http'

const visible = defineModel<boolean>({ default: false })
const oldPassword = ref('')
const newPassword = ref('')
const saving = ref(false)
const store = useAuthStore()

watch(visible, v => {
  if (!v) reset()
})

function reset() {
  oldPassword.value = ''
  newPassword.value = ''
}

async function save() {
  if (newPassword.value.length < 6) {
    toast.warning('新密码至少 6 位')
    return
  }
  saving.value = true
  try {
    await store.changePassword(oldPassword.value, newPassword.value)
    toast.success('密码已修改')
    visible.value = false
  } catch (e) {
    toast.error((e as ApiError).message)
  } finally {
    saving.value = false
  }
}
</script>
