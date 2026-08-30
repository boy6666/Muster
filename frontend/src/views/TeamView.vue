<template>
  <div>
    <el-space style="margin-bottom:12px" wrap>
      <el-select v-model="statusFilter" style="width:140px" @change="search">
        <el-option label="全部状态" value="" />
        <el-option label="待审核" value="PENDING" />
        <el-option label="已通过" value="CONFIRMED" />
        <el-option label="已驳回" value="REJECTED" />
        <el-option label="草稿" value="DRAFT" />
      </el-select>
      <el-button type="primary" @click="openCreate">新建组</el-button>
      <el-button @click="openPersonSearch">人员搜索</el-button>
    </el-space>

    <el-table :data="records" border>
      <el-table-column prop="name" label="组名" width="110" />
      <el-table-column label="组长" width="100">
        <template #default="{ row }">{{ (row as TeamAdminResponse).leaderName ?? '—' }}</template>
      </el-table-column>
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
      <el-table-column label="操作" min-width="260">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="success"
                     @click="pass(row as TeamAdminResponse)">通过</el-button>
          <el-button v-if="row.status === 'PENDING'" link type="danger"
                     @click="askReject(row as TeamAdminResponse)">驳回</el-button>
          <el-button link type="primary" @click="openDetail(row as TeamAdminResponse)">详情</el-button>
          <el-button link type="danger" @click="removeTeam(row as TeamAdminResponse)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   @current-change="p => { page = p; load() }" />

    <el-drawer v-model="detailVisible" :title="detail?.name" size="460px">
      <template v-if="detail">
        <el-alert v-if="detail.status === 'REJECTED' && detail.rejectReason"
                  type="error" :title="`驳回理由：${detail.rejectReason}`" :closable="false" />
        <el-alert v-if="detail.overLimit" type="warning" title="该组人数超出上限" :closable="false" />
        <h4>成员（{{ detail.members.length }} 人）</h4>
        <el-table :data="detail.members" size="small" border>
          <el-table-column prop="employeeId" label="员工编号" width="100" />
          <el-table-column label="姓名" width="110">
            <template #default="{ row }">
              {{ row.name }}
              <el-tag v-if="row.isLeader" size="small">组长</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="phone" label="手机号" width="120" />
          <el-table-column prop="department" label="部门" />
        </el-table>
        <el-space style="margin:12px 0">
          <el-button size="small" type="primary" @click="openMemberEditor()">管理员改组</el-button>
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

    <el-dialog v-model="memberVisible"
               :title="memberMode === 'create' ? '新建组（保存后直接通过）' : '管理员改组（保存后状态直接置为已通过）'"
               width="640px">
      <el-input v-model="searchKw" placeholder="员工编号 / 姓名 / 手机号 / 部门 模糊搜索花名册" clearable
                @keyup.enter="doSearch">
        <template #append>
          <el-button @click="doSearch">搜索</el-button>
        </template>
      </el-input>
      <el-table :data="searchResults" size="small" max-height="220" style="margin-top:8px">
        <el-table-column prop="employeeId" label="员工编号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="department" label="部门" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="pick(row as PersonRow)">加入</el-button>
          </template>
        </el-table-column>
      </el-table>
      <h4>已选成员（{{ picked.length }}）</h4>
      <el-table :data="picked" size="small" max-height="220">
        <el-table-column prop="employeeId" label="员工编号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="department" label="部门" />
        <el-table-column label="组长" width="70">
          <template #default="{ row }">
            <el-radio :model-value="leaderEmployeeId" :value="(row as PickedRow).employeeId"
                      @change="leaderEmployeeId = (row as PickedRow).employeeId">&nbsp;</el-radio>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="unpick(row as PickedRow)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="memberVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMembers">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="personVisible" title="人员搜索" width="640px">
      <el-input v-model="personKw" placeholder="员工编号 / 姓名 / 手机号 / 部门" clearable
                @keyup.enter="doPersonSearch">
        <template #append>
          <el-button @click="doPersonSearch">搜索</el-button>
        </template>
      </el-input>
      <el-table :data="personResults" size="small" max-height="320" style="margin-top:8px">
        <el-table-column prop="employeeId" label="员工编号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="department" label="部门" />
        <el-table-column label="所在组" width="110">
          <template #default="{ row }">{{ (row as PersonRow).teamName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="(row as PersonRow).teamId != null" link type="primary"
                       @click="viewTeam(row as PersonRow)">查看组</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type ApiError } from '../api/http'
import type { PageResult, PersonRow, TeamAdminResponse, TeamDetail, TeamEventView } from '../api/types'

/** 建组/改组对话框里已选成员的行（取花名册四要素） */
type PickedRow = { employeeId: string; name: string; phone: string; department: string }

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

const memberVisible = ref(false)
const memberMode = ref<'create' | 'edit'>('create')
const memberTarget = ref<number | null>(null)
const searchKw = ref('')
const searchResults = ref<PersonRow[]>([])
const picked = ref<PickedRow[]>([])
const leaderEmployeeId = ref('')

const personVisible = ref(false)
const personKw = ref('')
const personResults = ref<PersonRow[]>([])

const STATUS_TEXT: Record<string, string> =
  ({ DRAFT: '草稿', PENDING: '待审核', CONFIRMED: '已通过', REJECTED: '已驳回' })
const STATUS_TYPE: Record<string, string> =
  ({ DRAFT: 'info', PENDING: 'warning', CONFIRMED: 'success', REJECTED: 'danger' })
const EVENT_TEXT: Record<string, string> = {
  CREATED: '创建组',
  SAVED: '组长保存',
  SUBMITTED: '提交报名',
  EDITED_BY_ADMIN: '管理员修改',
  CREATED_BY_ADMIN: '管理员创建',
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

async function openDetail(team: { id: number }) {
  detailId = team.id
  detailVisible.value = true
  const [d, e] = await Promise.all([
    http.get<TeamDetail>(`/api/teams/${team.id}`),
    http.get<TeamEventView[]>(`/api/teams/${team.id}/events`),
  ])
  detail.value = d.data
  events.value = e.data
}

async function removeTeam(team: TeamAdminResponse) {
  try {
    await ElMessageBox.confirm(`删除 ${team.name}？组员将回到未报名状态`, '删除组', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/api/teams/${team.id}`)
    ElMessage.success('已删除')
    if (detailVisible.value && detailId === team.id) detailVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function resetMemberDialog() {
  picked.value = []
  leaderEmployeeId.value = ''
  searchKw.value = ''
  searchResults.value = []
}

function openCreate() {
  memberMode.value = 'create'
  memberTarget.value = null
  resetMemberDialog()
  memberVisible.value = true
}

function openMemberEditor(id?: number) {
  memberMode.value = 'edit'
  memberTarget.value = id ?? detailId
  resetMemberDialog()
  picked.value = (detail.value?.members ?? [])
    .map(m => ({ employeeId: m.employeeId, name: m.name, phone: m.phone, department: m.department }))
  leaderEmployeeId.value = detail.value?.members.find(m => m.isLeader)?.employeeId
    ?? picked.value[0]?.employeeId ?? ''
  memberVisible.value = true
}

async function doSearch() {
  if (!searchKw.value.trim()) return
  const { data } = await http.get<PageResult<PersonRow>>('/api/roster', {
    params: { keyword: searchKw.value.trim(), page: 1, size: 20 },
  })
  searchResults.value = data.records
}

function pick(row: PersonRow) {
  if (picked.value.some(p => p.employeeId === row.employeeId)) return
  picked.value.push({ employeeId: row.employeeId, name: row.name, phone: row.phone, department: row.department })
  if (!leaderEmployeeId.value) leaderEmployeeId.value = row.employeeId
}

function unpick(row: PickedRow) {
  picked.value = picked.value.filter(p => p.employeeId !== row.employeeId)
  if (leaderEmployeeId.value === row.employeeId) leaderEmployeeId.value = picked.value[0]?.employeeId ?? ''
}

async function saveMembers() {
  if (!picked.value.length) {
    ElMessage.warning('请先选择成员')
    return
  }
  const body = { leaderEmployeeId: leaderEmployeeId.value,
    memberEmployeeIdList: picked.value.map(p => p.employeeId) }
  try {
    if (memberMode.value === 'create') {
      await http.post('/api/teams', body)
      ElMessage.success('已创建，状态置为已通过')
    } else {
      if (memberTarget.value == null) return
      await http.put(`/api/teams/${memberTarget.value}/members`, body)
      ElMessage.success('已保存，状态置为已通过')
    }
    memberVisible.value = false
    await load()
    if (detailVisible.value && detailId) await openDetail({ id: detailId })
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function openPersonSearch() {
  personKw.value = ''
  personResults.value = []
  personVisible.value = true
}

async function doPersonSearch() {
  if (!personKw.value.trim()) return
  const { data } = await http.get<PageResult<PersonRow>>('/api/roster', {
    params: { keyword: personKw.value.trim(), page: 1, size: 20 },
  })
  personResults.value = data.records
}

async function viewTeam(row: PersonRow) {
  if (row.teamId == null) return
  personVisible.value = false
  await openDetail({ id: row.teamId })
}

defineExpose({ statusFilter, page, records, rejectReason, rejectVisible, rejectTarget,
  memberVisible, searchKw, searchResults, picked, leaderEmployeeId,
  personVisible, personKw, personResults, detailVisible,
  load, pass, askReject, submitReject, openDetail, removeTeam,
  openCreate, openMemberEditor, doSearch, pick, unpick, saveMembers,
  openPersonSearch, doPersonSearch, viewTeam })

load()
</script>
