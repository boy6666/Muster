<template>
  <!-- 无活动：创建表单 -->
  <div v-if="!activity" class="panel corner pad create-panel">
    <div class="p-title">创建活动 · NEW ACTIVITY</div>
    <form @submit.prevent="create">
      <label class="f-label">活动名称</label>
      <input v-model="form.name" class="input" placeholder="如：迎新晚会" />
      <label class="f-label">开始时间</label>
      <input v-model="form.startTime" type="datetime-local" class="input" />
      <label class="f-label">结束时间</label>
      <input v-model="form.endTime" type="datetime-local" class="input" />
      <label class="f-label">每组人数上限</label>
      <input v-model.number="form.groupSizeLimit" type="number" class="input" min="1" max="99" />
      <div class="act-actions">
        <button class="btn primary" type="submit">✚ 创建活动</button>
      </div>
    </form>
  </div>

  <div v-else class="grid-21">
    <div class="panel corner pad">
      <div class="p-title">活动信息 · ACTIVITY</div>
      <div class="act-head">
        <span class="act-name">{{ activity.name }}</span>
        <span class="tag" :class="statusTag">{{ activity.windowStatus === 'ACTIVE' ? '● ' : '' }}{{ statusText }}</span>
      </div>
      <div class="kv"><span class="k">开始时间</span><span class="v mono">{{ fmt(activity.startTime) }}</span></div>
      <div class="kv"><span class="k">结束时间</span><span class="v mono">{{ fmt(activity.endTime) }}</span></div>
      <div class="kv"><span class="k">每组人数上限</span><span class="v mono">{{ activity.groupSizeLimit }} 人</span></div>
      <div class="kv">
        <span class="k">归档状态</span>
        <span class="v">
          <span class="tag" :class="activity.exported ? 'ok' : 'dim'">
            {{ activity.exported ? '已导出归档包' : '未导出' }}
          </span>
          <span v-if="!activity.exported" class="hint">归档前无法新建 / 删除活动</span>
        </span>
      </div>

      <template v-if="activity.windowStatus === 'ACTIVE'">
        <div class="f-label" style="margin-top:20px">距活动结束</div>
        <div class="cd-big">{{ countdown }}</div>
        <div class="progress"><i :style="{ width: progressPct + '%' }" /></div>
        <div class="dim" style="font-size:11.5px">时间窗口进度 {{ progressPct }}% · 也可随时手动结束</div>
      </template>

      <div class="act-actions">
        <button class="btn" data-test="edit-btn" @click="openEdit">✎ 修改</button>
        <button class="btn danger" data-test="end-btn" :disabled="activity.windowStatus === 'ENDED'"
                @click="end">■ 手动结束</button>
        <button class="btn primary" data-test="export-btn" @click="exportArchive">⇩ 导出归档包</button>
        <button class="btn danger" data-test="del-btn" @click="remove">删除活动</button>
      </div>
    </div>

    <div class="panel corner pad">
      <div class="p-title">报名二维码 · QR TOKEN</div>
      <div class="qr-box">
        <canvas ref="qrCanvas" width="180" height="180" />
        <div class="url-line">{{ formUrl }}</div>
        <div class="dim qr-hint">组长扫码 → 输入员工编号 → 建组邀人<br />组级能力令牌 capToken 全组共享</div>
      </div>
    </div>
  </div>

  <UiModal v-model:visible="editVisible" title="修改活动">
    <label class="f-label" style="margin-top:0">活动名称</label>
    <input v-model="editForm.name" class="input" />
    <label class="f-label">开始时间</label>
    <input v-model="editForm.startTime" type="datetime-local" class="input" :disabled="!timeEditable" />
    <label class="f-label">结束时间</label>
    <input v-model="editForm.endTime" type="datetime-local" class="input" :disabled="!timeEditable" />
    <label class="f-label">每组人数上限</label>
    <input v-model.number="editForm.groupSizeLimit" type="number" class="input" min="1" max="99" />
    <template #footer>
      <button class="btn ghost" @click="editVisible = false">取消</button>
      <button class="btn primary" data-test="save-edit" @click="saveEdit">保存</button>
    </template>
  </UiModal>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import QRCode from 'qrcode'
import { http, type ApiError } from '../api/http'
import { downloadFile } from '../api/download'
import { toast } from '../components/ui/toast'
import { confirm } from '../components/ui/confirm'
import UiModal from '../components/ui/UiModal.vue'
import type { ActivityResponse } from '../api/types'

const activity = ref<ActivityResponse | null>(null)
const formUrl = ref('')
const form = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const editVisible = ref(false)
const editForm = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const qrCanvas = ref<HTMLCanvasElement>()

const timeEditable = computed(() => activity.value?.windowStatus === 'NOT_STARTED')
const statusTag = computed(() =>
  ({ NOT_STARTED: 'info', ACTIVE: 'ok', ENDED: 'dim' } as const)[activity.value?.windowStatus ?? 'NOT_STARTED'])
const statusText = computed(() =>
  ({ NOT_STARTED: '未开始', ACTIVE: '进行中', ENDED: '已结束' } as const)[activity.value?.windowStatus ?? 'NOT_STARTED'])

/** datetime-local 值（YYYY-MM-DDTHH:mm）→ 后端格式：补上秒 */
function withSeconds(v: string): string {
  return v.length === 16 ? `${v}:00` : v
}
/** 后端时间 → datetime-local 回显：截到分钟 */
function toLocalInput(v: string): string {
  return v.slice(0, 16)
}

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

// 倒计时 / 窗口进度（仅 ACTIVE 展示），每秒走一次本地时钟
const now = ref(Date.now())
let ticker: number | undefined
const countdown = computed(() => {
  if (!activity.value) return '--:--:--'
  const s = Math.max(0, Math.floor((new Date(activity.value.endTime).getTime() - now.value) / 1000))
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
})
const progressPct = computed(() => {
  if (!activity.value) return 0
  const start = new Date(activity.value.startTime).getTime()
  const end = new Date(activity.value.endTime).getTime()
  if (!(end > start)) return 100
  return Math.min(100, Math.max(0, Math.round(((now.value - start) / (end - start)) * 100)))
})

async function load() {
  const { data } = await http.get<ActivityResponse | ''>('/api/activity')
  activity.value = data === '' ? null : data
  if (activity.value) {
    const { data: u } = await http.get<{ url: string }>('/api/activity/form-url')
    formUrl.value = u.url
    QRCode.toCanvas(qrCanvas.value!, formUrl.value).catch(() => { /* jsdom 无 2d 上下文 */ })
  }
}

async function create() {
  try {
    await http.post('/api/activity', {
      name: form.value.name,
      startTime: withSeconds(form.value.startTime),
      endTime: withSeconds(form.value.endTime),
      groupSizeLimit: form.value.groupSizeLimit,
    })
    toast.success('活动已创建')
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

function openEdit() {
  editForm.value = {
    name: activity.value!.name,
    startTime: toLocalInput(activity.value!.startTime),
    endTime: toLocalInput(activity.value!.endTime),
    groupSizeLimit: activity.value!.groupSizeLimit,
  }
  editVisible.value = true
}

async function saveEdit() {
  try {
    await http.put('/api/activity', {
      name: editForm.value.name,
      startTime: withSeconds(editForm.value.startTime),
      endTime: withSeconds(editForm.value.endTime),
      groupSizeLimit: editForm.value.groupSizeLimit,
    })
    toast.success('已保存')
    editVisible.value = false
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

async function end() {
  try {
    await confirm('结束后不可恢复，确认？', '手动结束')
  } catch {
    return
  }
  try {
    await http.post('/api/activity/end')
    toast.success('已结束')
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

async function exportArchive() {
  try {
    await downloadFile('/api/activity/export/archive', '归档包.xlsx', 'POST')
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

async function remove() {
  try {
    await confirm('删除后花名册与分组一并清除，且不可恢复', '删除活动', 'danger')
  } catch {
    return
  }
  try {
    await http.delete('/api/activity')
    toast.success('已删除')
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

onMounted(() => {
  load()
  ticker = window.setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => window.clearInterval(ticker))
</script>

<style scoped>
.create-panel { max-width: 520px; }
.input { width: 100%; display: block; }
.act-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.act-name { font-size: 20px; font-weight: 700; }
.act-actions { display: flex; gap: 10px; margin-top: 20px; flex-wrap: wrap; }
.hint { margin-left: 8px; font-size: 12px; color: var(--text-3); }
.qr-hint { font-size: 11.5px; text-align: center; line-height: 1.8; }
</style>
