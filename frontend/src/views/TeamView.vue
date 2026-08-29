<template>
  <div>
    <el-space style="margin-bottom:12px">
      <el-select v-model="statusFilter" style="width:140px" @change="search">
        <el-option label="全部状态" value="" />
        <el-option label="待审核" value="PENDING" />
        <el-option label="已通过" value="CONFIRMED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
    </el-space>

    <el-table :data="records" border>
      <el-table-column prop="name" label="组名" width="110" />
      <el-table-column label="人数" width="120">
        <template #default="{ row }">
          {{ row.size }}
          <el-tag v-if="row.overLimit" type="warning" size="small">超员</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ fmt(row.submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="240">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="success"
                     @click="pass(row as TeamAdminResponse)">通过</el-button>
          <el-button link type="danger" @click="askReject(row as TeamAdminResponse)">驳回</el-button>
          <el-button link type="primary" @click="openDetail(row as TeamAdminResponse)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   @current-change="p => { page = p; load() }" />

    <el-drawer v-model="detailVisible" :title="detail?.name" size="420px">
      <template v-if="detail">
        <el-alert v-if="detail.status === 'REJECTED' && detail.rejectReason"
                  type="error" :title="`驳回理由：${detail.rejectReason}`" :closable="false" />
        <el-alert v-if="detail.overLimit" type="warning" title="该组人数超出上限" :closable="false" />
        <h4>成员（{{ detail.members.length }} 人）</h4>
        <el-table :data="detail.members" size="small" border>
          <el-table-column prop="name" label="姓名" width="80" />
          <el-table-column prop="phone" label="手机号" width="120" />
          <el-table-column prop="department" label="部门" />
        </el-table>
        <el-space style="margin:12px 0">
          <el-button size="small" type="primary" @click="openMemberEditor">管理员改组</el-button>
        </el-space>
        <h4>生命周期</h4>
        <el-timeline>
          <el-timeline-item v-for="ev in events" :key="ev.id" :timestamp="fmt(ev.createdAt)">
            {{ eventTypeText(ev.type) }}<span v-if="ev.detail">：{{ ev.detail }}</span>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <el-dialog v-model="rejectVisible" title="驳回" width="420px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="必填：说明驳回理由" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" @click="submitReject">驳回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="管理员改组（保存后状态直接置为已通过）" width="560px">
      <el-input v-model="searchKw" placeholder="姓名 / 手机号 / 部门 模糊搜索花名册" clearable
                @keyup.enter="doSearch">
        <template #append>
          <el-button @click="doSearch">搜索</el-button>
        </template>
      </el-input>
      <el-table :data="searchResults" size="small" max-height="220" style="margin-top:8px">
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="department" label="部门" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="pick(row as TeamMemberView)">加入</el-button>
          </template>
        </el-table-column>
      </el-table>
      <h4>已选成员（{{ picked.length }}）</h4>
      <el-tag v-for="m in picked" :key="m.phone" closable style="margin:0 8px 8px 0"
              @close="unpick(m)">
        {{ m.name }} {{ m.phone }}
      </el-tag>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMembers">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type ApiError } from '../api/http'
import type { PageResult, TeamAdminResponse, TeamDetail, TeamEventView, TeamMemberView } from '../api/types'

const statusFilter = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const records = ref<TeamAdminResponse[]>([])

const detailVisible = ref(false)
const detail = ref<TeamDetail | null>(null)
const events = ref<TeamEventView[]>([])
let detailId = 0

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<TeamAdminResponse | null>(null)

const editVisible = ref(false)
const searchKw = ref('')
const searchResults = ref<TeamMemberView[]>([])
const picked = ref<TeamMemberView[]>([])
const editTarget = ref<{ id: number } | null>(null)

const STATUS_TEXT: Record<string, string> =
  ({ PENDING: '待审核', CONFIRMED: '已通过', REJECTED: '已驳回' })
const STATUS_TYPE: Record<string, string> =
  ({ PENDING: 'warning', CONFIRMED: 'success', REJECTED: 'danger' })
const EVENT_TEXT: Record<string, string> = {
  SUBMITTED: '提交报名',
  EDITED_BY_LEADER: '组长修改',
  EDITED_BY_ADMIN: '管理员修改',
  PASSED: '审核通过',
  REJECTED: '驳回',
}

const statusType = (s: string) => (STATUS_TYPE[s] ?? 'info') as 'warning' | 'success' | 'danger' | 'info'
const statusText = (s: string) => STATUS_TEXT[s] ?? s
const eventTypeText = (t: string) => EVENT_TEXT[t] ?? t

function fmt(dt: string): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

async function load() {
  const { data } = await http.get<PageResult<TeamAdminResponse>>('/api/teams', {
    params: { status: statusFilter.value, page: page.value, size },
  })
  total.value = data.total
  records.value = data.records
}

function search() {
  page.value = 1
  load()
}

async function pass(team: TeamAdminResponse) {
  try {
    await ElMessageBox.confirm(`确认通过 ${team.name}？`, '审核', { type: 'success' })
  } catch {
    return
  }
  await review(team.id, { action: 'PASS' })
}

function askReject(team: TeamAdminResponse) {
  rejectTarget.value = team
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  if (!rejectReason.value.trim() || !rejectTarget.value) return
  await review(rejectTarget.value.id, { action: 'REJECT', reason: rejectReason.value.trim() })
  rejectVisible.value = false
}

async function review(teamId: number, body: Record<string, unknown>) {
  try {
    await http.put(`/api/teams/${teamId}/review`, body)
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function openDetail(team: TeamAdminResponse) {
  detailId = team.id
  detailVisible.value = true
  const [d, e] = await Promise.all([
    http.get<TeamDetail>(`/api/teams/${team.id}`),
    http.get<TeamEventView[]>(`/api/teams/${team.id}/events`),
  ])
  detail.value = d.data
  events.value = e.data
}

function openMemberEditor() {
  editTarget.value = { id: detailId }
  picked.value = detail.value ? detail.value.members.map(m => ({ ...m })) : []
  searchKw.value = ''
  searchResults.value = []
  editVisible.value = true
}

async function doSearch() {
  if (!searchKw.value.trim()) return
  const { data } = await http.get<PageResult<TeamMemberView & { id: number }>>('/api/roster', {
    params: { keyword: searchKw.value.trim(), page: 1, size: 20 },
  })
  searchResults.value = data.records
}

function pick(row: TeamMemberView) {
  if (!picked.value.some(m => m.phone === row.phone)) {
    picked.value.push({ name: row.name, phone: row.phone, department: row.department })
  }
}

function unpick(m: TeamMemberView) {
  picked.value = picked.value.filter(x => x.phone !== m.phone)
}

async function saveMembers() {
  if (!editTarget.value) return
  try {
    await http.put(`/api/teams/${editTarget.value.id}/members`,
      { memberPhoneList: picked.value.map(m => m.phone) })
    ElMessage.success('已保存，状态置为已通过')
    editVisible.value = false
    await load()
    if (detailVisible.value && detailId) {
      await openDetail({ id: detailId } as TeamAdminResponse)
    }
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

defineExpose({ statusFilter, page, records, keyword: searchKw, rejectReason,
               rejectVisible, rejectTarget, editTarget, picked,
               load, pass, askReject, submitReject, openDetail, saveMembers })

load()
</script>
