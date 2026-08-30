<template>
  <Teleport to="body">
    <div class="toast-host">
      <div v-for="t in toasts" :key="t.id" class="toast show" :class="'toast-' + t.type">
        <span class="toast-ico">{{ ICONS[t.type] }}</span><span>{{ t.msg }}</span>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { toasts, type ToastType } from './toast'

const ICONS: Record<ToastType, string> = { success: '✓', error: '✕', warning: '⚠' }
</script>
