<template>
  <div>
    <div class="toolbar">
      <button v-for="c in chips" :key="c.value" class="chip" :class="{ active: actionFilter === c.value }"
              @click="pick(c.value)">{{ c.label }}</button>
    </div>

    <div class="panel corner pad">
      <div class="p-title">操作流水 · OP LOG</div>
      <div v-for="row in records" :key="row.id" class="log-row">
        <span class="t mono">{{ fmt(row.createdAt) }}</span>
        <span class="op">{{ row.adminUsername }}</span>
        <span class="act"><span class="tag" :class="tagClass(row.action)">{{ ACTION_TEXT[row.action] ?? row.action }}</span></span>
        <span class="dt">{{ row.detail ?? '-' }}</span>
      </div>
      <div v-if="!records.length" class="dim" style="padding:10px 6px">暂无日志</div>
    </div>

    <UiPagination :total="total" :page="page" :size="size" @change="onPage" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { http } from '../api/http'
import type { OpLogView, PageResult } from '../api/types'
import UiPagination from '../components/ui/UiPagination.vue'

const actionFilter = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const records = ref<OpLogView[]>([])

const ACTION_TEXT: Record<string, string> = {
  ACTIVITY_CREATE: '创建活动',
  ACTIVITY_UPDATE: '修改活动',
  ACTIVITY_END: '手动结束',
  ACTIVITY_DELETE: '删除活动',
  ACTIVITY_ARCHIVE: '导出归档',
  ROSTER_IMPORT: '导入花名册',
  ROSTER_ADD: '添加人员',
  ROSTER_DELETE: '删除人员',
  TEAM_EDIT_ADMIN: '管理员改组',
  TEAM_REVIEW: '组审核',
}

// 操作 → tag 颜色:审核 warn / 改组与花名册 info / 活动建改 ok / 结束与删除 err / 归档 dim
const ACTION_TAG: Record<string, string> = {
  ACTIVITY_CREATE: 'ok',
  ACTIVITY_UPDATE: 'ok',
  ACTIVITY_END: 'err',
  ACTIVITY_DELETE: 'err',
  ACTIVITY_ARCHIVE: 'dim',
  ROSTER_IMPORT: 'info',
  ROSTER_ADD: 'info',
  ROSTER_DELETE: 'info',
  TEAM_EDIT_ADMIN: 'info',
  TEAM_REVIEW: 'warn',
}

const chips = [
  { value: '', label: '全部操作' },
  ...Object.entries(ACTION_TEXT).map(([value, label]) => ({ value, label })),
]

function tagClass(action: string): string {
  return ACTION_TAG[action] ?? 'dim'
}

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

async function load() {
  const { data } = await http.get<PageResult<OpLogView>>('/api/audit/logs', {
    params: { action: actionFilter.value, page: page.value, size },
  })
  total.value = data.total
  records.value = data.records
}

function pick(value: string) {
  actionFilter.value = value
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

load()
</script>

<style scoped>
/* 原型 .t 定宽 70px 只装得下时刻,这里放完整日期时间需放宽 */
.log-row .t {
  width: 118px;
}
</style>
