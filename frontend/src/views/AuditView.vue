<template>
  <div>
    <el-space style="margin-bottom:12px">
      <el-select v-model="actionFilter" style="width:180px" @change="search">
        <el-option label="全部操作" value="" />
        <el-option v-for="(text, key) in ACTION_TEXT" :key="key" :label="text" :value="key" />
      </el-select>
    </el-space>

    <el-table :data="records" border>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="adminUsername" label="操作人" width="110" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">{{ row.action }}</template>
      </el-table-column>
      <el-table-column label="详情">
        <template #default="{ row }">{{ row.detail ?? '-' }}</template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   @current-change="p => { page = p; load() }" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { http } from '../api/http'
import type { OpLogView, PageResult } from '../api/types'

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

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

async function load() {
  const { data } = await http.get<PageResult<OpLogView>>('/api/audit', {
    params: { action: actionFilter.value, page: page.value, size },
  })
  total.value = data.total
  records.value = data.records
}

function search() {
  page.value = 1
  load()
}

defineExpose({ actionFilter, page, records, load, search })

load()
</script>
