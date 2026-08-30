<template>
  <div class="pagi">
    <button class="pg pg-nav" :disabled="page <= 1" aria-label="上一页" @click="go(page - 1)">‹</button>
    <template v-for="(it, i) in pages" :key="i + '-' + it">
      <span v-if="it === '…'" class="pg pg-ellipsis">…</span>
      <button v-else class="pg" :class="{ active: it === page }" @click="go(it)">{{ it }}</button>
    </template>
    <button class="pg pg-nav" :disabled="page >= totalPages" aria-label="下一页" @click="go(page + 1)">›</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  total: number
  page: number
  size: number
}>()

const emit = defineEmits<{ (e: 'change', page: number): void }>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

// 超过 7 页时以当前页为中心开窗,断档处用省略号
const pages = computed<(number | '…')[]>(() => {
  const total = totalPages.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const set = new Set<number>([1, 2, props.page - 1, props.page, props.page + 1, total - 1, total])
  const list = [...set].filter(p => p >= 1 && p <= total).sort((a, b) => a - b)
  const out: (number | '…')[] = []
  let prev = 0
  for (const p of list) {
    if (p - prev > 1) out.push('…')
    out.push(p)
    prev = p
  }
  return out
})

function go(p: number) {
  if (p < 1 || p > totalPages.value || p === props.page) return
  emit('change', p)
}
</script>
