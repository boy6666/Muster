<template>
  <div v-if="!active" class="panel corner empty">
    <div class="empty-ico">◈</div>
    <p class="empty-title">没有进行中的活动</p>
    <p class="empty-sub">请在「活动管理」创建活动并使其处于进行中状态,此处将呈现实时统计。</p>
  </div>
  <div v-else>
    <div class="panel corner banner">
      <span class="live-dot"></span>
      <span class="act-name">当前活动：{{ activity?.name ?? '—' }}</span>
      <span class="tag ok">进行中</span>
      <span class="tag info">每组上限 {{ activity?.groupSizeLimit ?? '-' }} 人</span>
      <span v-if="countdown" class="cd">距结束 {{ countdown }}</span>
    </div>

    <div class="stat-grid four">
      <div v-for="card in cards" :key="card.label" class="panel corner stat">
        <div class="k">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path v-for="(p, i) in card.icon" :key="i" :d="p" />
          </svg>
          {{ card.label }}
        </div>
        <div class="v flash" :key="String(card.value)" :class="card.cls">{{ card.value }}</div>
        <div class="d">{{ card.d }}</div>
      </div>
    </div>

    <div class="grid-32">
      <div class="panel corner pad">
        <div class="p-title">实时动态 · LIVE FEED</div>
        <div class="feed">
          <div v-if="recentEvents.length === 0" class="dim feed-empty">暂无动态,等待各组报名…</div>
          <div v-for="(ev, i) in recentEvents" :key="`${ev.teamId}-${ev.createdAt}-${i}`" class="feed-row">
            <span class="t">{{ ev.createdAt.slice(11, 19) }}</span>
            <span class="who mono">{{ ev.teamName }}</span>
            <span class="what">{{ EVENT_TEXT[ev.type] ?? ev.type }}</span>
            <span class="x">{{ ev.detail }}</span>
          </div>
        </div>
      </div>
      <div>
        <div class="panel corner pad right-panel">
          <div class="p-title">参加率</div>
          <div class="donut-wrap">
            <svg width="128" height="128" viewBox="0 0 128 128">
              <defs>
                <linearGradient id="donut-grad" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#06b6d4" /><stop offset="1" stop-color="#7c3aed" />
                </linearGradient>
              </defs>
              <circle cx="64" cy="64" r="52" fill="none" stroke="rgba(8,145,178,.14)" stroke-width="11" />
              <circle cx="64" cy="64" r="52" fill="none" stroke="url(#donut-grad)" stroke-width="11"
                stroke-linecap="round" :stroke-dasharray="dash" transform="rotate(-90 64 64)" />
              <text x="64" y="60" text-anchor="middle" fill="#101d33" font-size="22"
                font-family="monospace" font-weight="600">{{ pct }}%</text>
              <text x="64" y="80" text-anchor="middle" fill="#64748b" font-size="10">JOIN RATE</text>
            </svg>
            <div class="legend">
              <div><i style="background:var(--green)"></i>已参加 <b class="mono">{{ stats?.registered ?? 0 }}</b></div>
              <div><i style="background:rgba(100,116,139,.4)"></i>未参加 <b class="mono">{{ stats?.notRegistered ?? 0 }}</b></div>
              <div>参加率 <b class="mono">{{ pct }}%</b></div>
              <div class="legend-btns">
                <button class="btn sm primary" data-test="export-joined" @click="exportJoined">导出已参加</button>
                <button class="btn sm" data-test="export-missing" @click="exportMissing">导出未参加</button>
              </div>
            </div>
          </div>
        </div>
        <div class="panel corner pad">
          <div class="p-title">组人数分布</div>
          <div class="bars">
            <div v-for="b in distribution" :key="b.size" class="bar" :class="{ over: b.overLimit }">
              <i :style="{ height: barHeight(b) }"></i>
              <b>{{ b.size }}人 · ×{{ b.count }}</b>
            </div>
          </div>
          <div v-if="distribution.length === 0" class="dim" style="font-size:12px;margin-top:8px">暂无分组数据</div>
          <div v-if="overCount > 0" class="over-warn">⚠ {{ overCount }} 个组超出 {{ activity?.groupSizeLimit }} 人上限</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { http } from '../api/http'
import { downloadFile } from '../api/download'
import { useStats } from '../composables/useStats'
import type { ActivityResponse, RecentEvent, SizeBucket } from '../api/types'

const { stats, start, stop } = useStats()
const activity = ref<ActivityResponse | null>(null)
const distribution = ref<SizeBucket[]>([])
const active = computed(() => activity.value?.windowStatus === 'ACTIVE')

const EVENT_TEXT: Record<string, string> = {
  CREATED: '创建组',
  SAVED: '组长保存',
  SUBMITTED: '提交报名',
  EDITED_BY_ADMIN: '管理员修改',
  CREATED_BY_ADMIN: '管理员创建',
  PASSED: '审核通过',
  REJECTED: '驳回',
}

const recentEvents = computed<RecentEvent[]>(() => stats.value?.recentEvents ?? [])
const cards = computed(() => [
  { label: '已报名', value: stats.value?.registered ?? '-', cls: 'green', d: '已加入非草稿组',
    icon: ['M20 6L9 17l-5-5'] },
  { label: '未报名', value: stats.value?.notRegistered ?? '-', cls: '', d: '尚未加入任何组',
    icon: ['M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18', 'M12 7v5l3 3'] },
  { label: '分组数', value: stats.value?.teamCount ?? '-', cls: '', d: '含草稿组',
    icon: ['M12 2l8 3.5v5c0 5-3.4 9.4-8 11-4.6-1.6-8-6-8-11v-5L12 2z'] },
  { label: '待审核', value: stats.value?.pendingTeamCount ?? '-', cls: 'amber', d: '等待管理员审核',
    icon: ['M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18', 'M12 8v4', 'M12 16h.01'] },
])

/* ---- 参加率环 ---- */
const CIRC = 2 * Math.PI * 52
const pct = computed(() => {
  const s = stats.value
  if (!s || s.total <= 0) return 0
  return Math.round((s.registered / s.total) * 100)
})
const dash = computed(() => {
  const filled = (pct.value / 100) * CIRC
  return `${filled.toFixed(1)} ${(CIRC - filled).toFixed(1)}`
})

/* ---- 组人数分布 ---- */
function barHeight(b: SizeBucket): string {
  const max = Math.max(...distribution.value.map(x => x.count), 1)
  return `${Math.max(6, Math.round((b.count / max) * 56))}px`
}
const overCount = computed(() =>
  distribution.value.filter(b => b.overLimit).reduce((s, b) => s + b.count, 0))

/* ---- 距结束倒计时(ENDED/无 endTime 不显示) ---- */
const nowMs = ref(Date.now())
let cdTimer: ReturnType<typeof setInterval> | null = null
const countdown = computed(() => {
  const end = activity.value?.endTime
  if (!end) return ''
  const diff = new Date(end).getTime() - nowMs.value
  if (diff <= 0) return ''
  const two = (n: number) => String(n).padStart(2, '0')
  return `${two(Math.floor(diff / 3600000))}:${two(Math.floor((diff % 3600000) / 60000))}:${two(Math.floor((diff % 60000) / 1000))}`
})

const exportJoined = () => downloadFile('/api/stats/export?type=JOINED', '已参加.xlsx')
const exportMissing = () => downloadFile('/api/stats/export?type=MISSING', '未参加.xlsx')

onMounted(async () => {
  try {
    const { data } = await http.get<ActivityResponse | ''>('/api/activity')
    activity.value = data === '' ? null : data
  } catch {
    activity.value = null
  }
  if (active.value) {
    start()
    http.get<SizeBucket[]>('/api/stats/distribution')
      .then(({ data }) => { distribution.value = data })
      .catch(() => { /* WS/刷新流程会补上,页面留空即可 */ })
    cdTimer = setInterval(() => { nowMs.value = Date.now() }, 1000)
  }
})
onUnmounted(() => {
  stop()
  if (cdTimer) { clearInterval(cdTimer); cdTimer = null }
})
</script>

<style scoped>
.stat-grid.four { grid-template-columns: repeat(4, 1fr); }
.right-panel { margin-bottom: 14px; }
.legend-btns { margin-top: 8px; display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.over-warn { font-size: 11px; color: var(--amber); margin-top: 10px; }
.feed-empty { padding: 22px 4px; font-size: 13px; }
.empty { max-width: 560px; margin: 10vh auto 0; text-align: center; padding: 52px 32px; }
.empty-ico { font-size: 40px; color: var(--cyan); opacity: .6; }
.empty-title { font-size: 16px; font-weight: 600; margin-top: 12px; letter-spacing: .06em; }
.empty-sub { color: var(--text-3); font-size: 12.5px; margin-top: 8px; }
</style>
