<template>
  <div v-if="!activity">
    <h3>创建活动</h3>
    <el-form style="max-width:480px" @submit.prevent="create">
      <el-form-item label="活动名称">
        <el-input v-model="form.name" placeholder="如：迎新晚会" />
      </el-form-item>
      <el-form-item label="开始时间">
        <el-date-picker v-model="form.startTime" type="datetime"
                        value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" />
      </el-form-item>
      <el-form-item label="结束时间">
        <el-date-picker v-model="form.endTime" type="datetime"
                        value-format="YYYY-MM-DDTHH:mm:ss" />
      </el-form-item>
      <el-form-item label="每组人数上限">
        <el-input-number v-model="form.groupSizeLimit" :min="1" :max="99" />
      </el-form-item>
      <el-button type="primary" native-type="submit">创建</el-button>
    </el-form>
  </div>

  <div v-else>
    <el-descriptions :title="activity.name" :column="2" border>
      <el-descriptions-item label="开始时间">{{ fmt(activity.startTime) }}</el-descriptions-item>
      <el-descriptions-item label="结束时间">{{ fmt(activity.endTime) }}</el-descriptions-item>
      <el-descriptions-item label="每组上限">{{ activity.groupSizeLimit }} 人</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="statusType">{{ statusText }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="归档">
        {{ activity.exported ? '已导出归档包' : '未导出' }}
      </el-descriptions-item>
    </el-descriptions>

    <el-space style="margin:16px 0">
      <el-button @click="openEdit">修改</el-button>
      <el-button data-test="end-btn" type="warning"
                 :disabled="activity.windowStatus === 'ENDED'" @click="end">
        手动结束
      </el-button>
      <el-button type="primary" @click="exportArchive">导出归档包</el-button>
      <el-button type="danger" @click="remove">删除活动</el-button>
    </el-space>

    <el-card header="报名二维码" class="qr-card">
      <canvas ref="qrCanvas" />
      <p>{{ formUrl }}</p>
    </el-card>

    <el-dialog v-model="editVisible" title="修改活动">
      <el-form label-width="110px">
        <el-form-item label="活动名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="editForm.startTime" type="datetime" :disabled="!timeEditable"
                          value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="editForm.endTime" type="datetime" :disabled="!timeEditable"
                          value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="每组人数上限">
          <el-input-number v-model="editForm.groupSizeLimit" :min="1" :max="99" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { http, type ApiError } from '../api/http'
import { downloadFile } from '../api/download'
import type { ActivityResponse } from '../api/types'

const activity = ref<ActivityResponse | null>(null)
const formUrl = ref('')
const form = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const editVisible = ref(false)
const editForm = ref({ name: '', startTime: '', endTime: '', groupSizeLimit: 5 })
const qrCanvas = ref<HTMLCanvasElement>()

const timeEditable = computed(() => activity.value?.windowStatus === 'NOT_STARTED')
const statusType = computed(() =>
  ({ NOT_STARTED: 'info', ACTIVE: 'success', ENDED: 'danger' } as const)[activity.value?.windowStatus ?? 'NOT_STARTED'])
const statusText = computed(() =>
  ({ NOT_STARTED: '未开始', ACTIVE: '进行中', ENDED: '已结束' } as const)[activity.value?.windowStatus ?? 'NOT_STARTED'])

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

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
    await http.post('/api/activity', form.value)
    ElMessage.success('活动已创建')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function openEdit() {
  editForm.value = {
    name: activity.value!.name,
    startTime: activity.value!.startTime,
    endTime: activity.value!.endTime,
    groupSizeLimit: activity.value!.groupSizeLimit,
  }
  editVisible.value = true
}

async function saveEdit() {
  try {
    await http.put('/api/activity', editForm.value)
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function end() {
  try {
    await ElMessageBox.confirm('结束后组长不能再报名或改组，确定结束？', '手动结束', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.post('/api/activity/end')
    ElMessage.success('已结束')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function exportArchive() {
  try {
    await downloadFile('/api/activity/export/archive', '归档包.xlsx', 'POST')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm('删除后花名册与分组一并清除，且不可恢复', '删除活动', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete('/api/activity')
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

defineExpose({ activity, form, formUrl, create, end, load })

onMounted(load)
</script>

<style scoped>
.qr-card { max-width: 360px; }
</style>
