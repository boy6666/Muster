<template>
  <div v-if="!active" class="placeholder">
    <el-empty description="当前没有进行中的活动" />
  </div>
  <div v-else>
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card class="stat-card">
          <div class="num">{{ card.value }}</div>
          <div class="label">{{ card.label }}</div>
        </el-card>
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
  try {
    const { data } = await http.get('/api/activity')
    windowStatus.value = data === '' ? null : data.windowStatus
  } catch {
    windowStatus.value = null
  }
  if (active.value) start()
})
onUnmounted(stop)
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-card .num { font-size: 32px; font-weight: 700; }
.stat-card .label { color: #909399; margin-top: 4px; }
</style>
