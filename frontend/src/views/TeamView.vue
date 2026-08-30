<template>
  <div>
    <div class="toolbar">
      <button v-for="c in CHIPS" :key="c.value" class="chip" :class="{ active: statusFilter === c.value }"
              @click="setFilter(c.value)">
        {{ c.label }}
        <span v-if="counts[c.value] != null" class="cnt mono">· {{ counts[c.value] }}</span>
      </button>
      <span style="flex:1"></span>
      <span class="dim" style="font-size:12px">审核不受活动窗口限制 · 驳回需填写理由</span>
      <button class="btn" @click="openPersonSearch">人员搜索</button>
      <button class="btn primary" @click="openCreate">新建组</button>
    </div>

    <div class="panel corner" style="overflow:hidden">
      <table class="tbl">
        <thead>
          <tr>
            <th>组名</th>
            <th>组长</th>
            <th>人数</th>
            <th>状态</th>
            <th>提交时间</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id">
            <td><b>{{ row.name }}</b></td>
            <td>{{ row.leaderName ?? '—' }}</td>
            <td class="mono">{{ row.size }}<span v-if="row.overLimit" class="tag warn" style="margin-left:6px">超员</span></td>
            <td><span class="tag" :class="statusClass(row.status)">{{ statusText(row.status) }}</span></td>
            <td class="mono dim">{{ fmt(row.submittedAt) }}</td>
            <td class="ops">
              <button v-if="row.status === 'PENDING'" class="link ok" @click="pass(row)">通过</button>
              <button v-if="row.status === 'PENDING'" class="link err" @click="askReject(row)">驳回</button>
              <button class="link" @click="openDetail(row)">详情</button>
              <button class="link err" @click="removeTeam(row)">删除</button>
            </td>
          </tr>
          <tr v-if="!records.length">
            <td colspan="6" class="empty">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div style="display:flex;align-items:center;justify-content:space-between;padding-top:14px">
      <span class="dim" style="font-size:12.5px">共 <b class="mono" style="color:var(--cyan)">{{ total }}</b> 组</span>
      <UiPagination :total="total" :page="page" :size="size" @change="onPage" />
    </div>

    <UiDrawer v-model:visible="detailVisible" :title="detail?.name ?? ''">
      <template v-if="detail">
        <span class="tag" :class="statusClass(detail.status)">{{ statusText(detail.status) }}</span>
        <div v-if="detail.status === 'REJECTED' && detail.rejectReason" class="alert" style="margin-top:12px">
          <span>⛔</span><span><b>驳回理由：</b>{{ detail.rejectReason }}</span>
        </div>
        <div v-if="detail.overLimit" class="alert warn" style="margin-top:12px">
          <span>⚠</span><span>该组人数超出上限（超员仅标记，提交未被拦截）</span>
        </div>
        <div class="p-title" style="margin-top:18px">成员 · {{ detail.members.length }} 人</div>
        <table class="tbl" style="margin-bottom:18px">
          <thead><tr><th>员工编号</th><th>姓名</th><th>手机号</th><th>部门</th></tr></thead>
          <tbody>
            <tr v-for="m in detail.members" :key="m.employeeId">
              <td class="mono">{{ m.employeeId }}</td>
              <td>{{ m.name }}<span v-if="m.isLeader" class="tag info" style="margin-left:4px">组长</span></td>
              <td class="mono">{{ m.phone }}</td>
              <td>{{ m.department }}</td>
            </tr>
          </tbody>
        </table>
        <div class="p-title">生命周期 · TIMELINE</div>
        <div class="tl">
          <div v-for="ev in events" :key="ev.id" class="tl-item">
            <div class="tt">{{ eventTypeText(ev.type) }}<span v-if="ev.detail">：{{ ev.detail }}</span></div>
            <div class="ts">{{ fmt(ev.createdAt) }}</div>
          </div>
          <div v-if="!events.length" class="dim" style="font-size:12px;padding-bottom:12px">暂无事件</div>
        </div>
      </template>
      <template #footer>
        <template v-if="detail">
          <button v-if="detail.status === 'PENDING'" class="btn ok" style="flex:1" @click="pass(detail)">✓ 通过</button>
          <button v-if="detail.status === 'PENDING'" class="btn danger" style="flex:1" @click="askReject(detail)">✕ 驳回</button>
          <button class="btn" style="flex:1" @click="openMemberEditor()">⚙ 管理员改组</button>
        </template>
      </template>
    </UiDrawer>

    <UiModal v-model:visible="rejectVisible" title="驳回" width="440px">
      <label class="f-label">驳回理由（必填）</label>
      <textarea v-model="rejectReason" class="input" rows="3" style="width:100%;resize:vertical"
                placeholder="必填：说明驳回理由"></textarea>
      <template #footer>
        <button class="btn ghost" @click="rejectVisible = false">取消</button>
        <button class="btn danger" @click="submitReject">驳回</button>
      </template>
    </UiModal>

    <UiModal v-model:visible="memberVisible" width="680px"
             :title="memberMode === 'create' ? '新建组（保存后直接通过）' : '管理员改组（保存后状态直接置为已通过）'">
      <div style="display:flex;gap:8px">
        <input v-model="searchKw" class="input" style="flex:1"
               placeholder="员工编号 / 姓名 / 手机号 / 部门 模糊搜索花名册" @keyup.enter="doSearch" />
        <button class="btn" @click="doSearch">搜索</button>
      </div>
      <table class="tbl" style="margin-top:10px">
        <thead><tr><th>员工编号</th><th>姓名</th><th>手机号</th><th>部门</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in searchResults" :key="row.id">
            <td class="mono">{{ row.employeeId }}</td>
            <td>{{ row.name }}</td>
            <td class="mono">{{ row.phone }}</td>
            <td>{{ row.department }}</td>
            <td class="ops"><button class="link" @click="pick(row)">加入</button></td>
          </tr>
          <tr v-if="!searchResults.length"><td colspan="5" class="empty">搜索花名册后点「加入」选人</td></tr>
        </tbody>
      </table>
      <div class="p-title">已选成员 · {{ picked.length }} 人</div>
      <table class="tbl">
        <thead><tr><th>员工编号</th><th>姓名</th><th>手机号</th><th>部门</th><th>组长</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in picked" :key="row.employeeId">
            <td class="mono">{{ row.employeeId }}</td>
            <td>{{ row.name }}</td>
            <td class="mono">{{ row.phone }}</td>
            <td>{{ row.department }}</td>
            <td>
              <input type="radio" name="leader" :checked="leaderEmployeeId === row.employeeId"
                     :aria-label="`设 ${row.name} 为组长`" @change="leaderEmployeeId = row.employeeId" />
            </td>
            <td class="ops"><button class="link err" @click="unpick(row)">移除</button></td>
          </tr>
          <tr v-if="!picked.length"><td colspan="6" class="empty">尚未选择成员</td></tr>
        </tbody>
      </table>
      <template #footer>
        <button class="btn ghost" @click="memberVisible = false">取消</button>
        <button class="btn primary" @click="saveMembers">保存</button>
      </template>
    </UiModal>

    <UiModal v-model:visible="personVisible" title="人员搜索" width="680px">
      <div style="display:flex;gap:8px">
        <input v-model="personKw" class="input" style="flex:1" placeholder="员工编号 / 姓名 / 手机号 / 部门"
               @keyup.enter="doPersonSearch" />
        <button class="btn" @click="doPersonSearch">搜索</button>
      </div>
      <table class="tbl" style="margin-top:10px">
        <thead><tr><th>员工编号</th><th>姓名</th><th>手机号</th><th>部门</th><th>所在组</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in personResults" :key="row.id">
            <td class="mono">{{ row.employeeId }}</td>
            <td>{{ row.name }}</td>
            <td class="mono">{{ row.phone }}</td>
            <td>{{ row.department }}</td>
            <td>{{ row.teamName ?? '—' }}</td>
            <td class="ops">
              <button v-if="row.teamId != null" class="link" @click="viewTeam(row)">查看组</button>
              <span v-else class="dim">—</span>
            </td>
          </tr>
          <tr v-if="!personResults.length"><td colspan="6" class="empty">无匹配结果</td></tr>
        </tbody>
      </table>
    </UiModal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import UiDrawer from '../components/ui/UiDrawer.vue'
import UiModal from '../components/ui/UiModal.vue'
import UiPagination from '../components/ui/UiPagination.vue'
import { confirm } from '../components/ui/confirm'
import { toast } from '../components/ui/toast'
import { http, type ApiError } from '../api/http'
import type { PageResult, PersonRow, TeamAdminResponse, TeamDetail, TeamEventView } from '../api/types'

/** 建组/改组对话框里已选成员的行（取花名册四要素） */
type PickedRow = { employeeId: string; name: string; phone: string; department: string }

const statusFilter = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const records = ref<TeamAdminResponse[]>([])

/** 筛选 chips：value 为 '' 表示全部 */
const CHIPS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审核' },
  { value: 'CONFIRMED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'DRAFT', label: '草稿' },
]
/** chips 内的计数；null 表示尚未取到（取数失败时静默不渲染） */
const counts = ref<Record<string, number | null>>({ '': null, PENDING: null, CONFIRMED: null, REJECTED: null, DRAFT: null })

const detailVisible = ref(false)
const detail = ref<TeamDetail | null>(null)
const events = ref<TeamEventView[]>([])
let detailId = 0

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<{ id: number; name: string } | null>(null)

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
const STATUS_CLASS: Record<string, string> =
  ({ DRAFT: 'info', PENDING: 'warn', CONFIRMED: 'ok', REJECTED: 'err' })
const EVENT_TEXT: Record<string, string> = {
  CREATED: '创建组',
  SAVED: '组长保存',
  SUBMITTED: '提交报名',
  EDITED_BY_ADMIN: '管理员修改',
  CREATED_BY_ADMIN: '管理员创建',
  PASSED: '审核通过',
  REJECTED: '驳回',
}

const statusClass = (s: string) => STATUS_CLASS[s] ?? 'info'
const statusText = (s: string) => STATUS_TEXT[s] ?? s
const eventTypeText = (t: string) => EVENT_TEXT[t] ?? t

function fmt(dt: string | null | undefined): string {
  return dt?.replace('T', ' ').slice(0, 16) ?? ''
}

async function load() {
  const { data } = await http.get<PageResult<TeamAdminResponse>>('/api/teams', {
    params: { status: statusFilter.value, page: page.value, size },
  })
  total.value = data.total
  records.value = data.records
}

/** 并行取 5 个 chip 的计数（size=1 只为拿 total），单个失败静默留空 */
async function loadCounts() {
  await Promise.all(CHIPS.map(async ({ value }) => {
    try {
      const { data } = await http.get<PageResult<TeamAdminResponse>>('/api/teams', {
        params: { status: value, page: 1, size: 1 },
      })
      counts.value[value] = data.total
    } catch {
      // 计数取不到就不渲染
    }
  }))
}

/** 审核后列表与计数一起刷新 */
async function refresh() {
  await Promise.all([load(), loadCounts()])
}

function search() {
  page.value = 1
  load()
}

function setFilter(v: string) {
  statusFilter.value = v
  search()
}

function onPage(p: number) {
  page.value = p
  load()
}

async function pass(team: { id: number; name: string }) {
  try {
    await confirm(`确认通过 ${team.name}？`, '审核')
  } catch {
    return
  }
  await review(team.id, { action: 'PASS' })
}

function askReject(team: { id: number; name: string }) {
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
    await refresh()
  } catch (e) {
    toast.error((e as ApiError).message)
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
    await confirm(`删除 ${team.name}？组员将回到未报名状态`, '删除组')
  } catch {
    return
  }
  try {
    await http.delete(`/api/teams/${team.id}`)
    toast.success('已删除')
    if (detailVisible.value && detailId === team.id) detailVisible.value = false
    await refresh()
  } catch (e) {
    toast.error((e as ApiError).message)
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
    toast.warning('请先选择成员')
    return
  }
  const body = { leaderEmployeeId: leaderEmployeeId.value,
    memberEmployeeIdList: picked.value.map(p => p.employeeId) }
  try {
    if (memberMode.value === 'create') {
      await http.post('/api/teams', body)
      toast.success('已创建，状态置为已通过')
    } else {
      if (memberTarget.value == null) return
      await http.put(`/api/teams/${memberTarget.value}/members`, body)
      toast.success('已保存，状态置为已通过')
    }
    memberVisible.value = false
    await refresh()
    if (detailVisible.value && detailId) await openDetail({ id: detailId })
  } catch (e) {
    toast.error((e as ApiError).message)
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

defineExpose({ statusFilter, page, total, records, counts, detail, events, detailVisible,
  rejectReason, rejectVisible, rejectTarget, memberVisible, searchKw, searchResults, picked, leaderEmployeeId,
  personVisible, personKw, personResults,
  load, loadCounts, setFilter, pass, askReject, submitReject, openDetail, removeTeam,
  openCreate, openMemberEditor, doSearch, pick, unpick, saveMembers,
  openPersonSearch, doPersonSearch, viewTeam })

load()
loadCounts()
</script>

<style scoped>
.ops { text-align: right; }
th.ops { text-align: right; }
.empty { text-align: center; color: var(--text-3); padding: 26px 0; }
.cnt { font-size: 12px; opacity: .8; }
input[type='radio'] { accent-color: #06b6d4; cursor: pointer; width: 15px; height: 15px; }
</style>
