<template>
  <div>
    <div class="toolbar">
      <input v-model="keyword" class="input" style="width:250px" placeholder="员工编号 / 姓名 / 手机号 / 部门"
             @keyup.enter="search" />
      <button class="btn primary" @click="search">查询</button>
      <span style="flex:1"></span>
      <button class="btn" @click="downloadTemplate">下载模板</button>
      <button class="btn primary" @click="importVisible = true">⇪ 导入 Excel</button>
      <button class="btn" @click="openAdd">＋ 添加人员</button>
      <button class="btn danger" @click="clearRoster">一键清空</button>
    </div>

    <div class="panel corner" style="overflow:hidden">
      <table class="tbl">
        <thead>
          <tr>
            <th>员工编号</th>
            <th>姓名</th>
            <th>手机号</th>
            <th>部门</th>
            <th>组别</th>
            <th>组长</th>
            <th>状态</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id">
            <td class="mono">{{ row.employeeId }}</td>
            <td>{{ row.name }}</td>
            <td class="mono">{{ row.phone }}</td>
            <td>{{ row.department }}</td>
            <td>{{ row.teamName ?? '—' }}</td>
            <td>{{ row.leaderName ?? '—' }}</td>
            <td>
              <span class="tag" :class="row.participated ? 'ok' : 'dim'">
                {{ row.participated ? '已参加' : '未参加' }}
              </span>
            </td>
            <td style="text-align:right">
              <button class="link ok" @click="startEdit(row)">编辑</button>
              <button class="link err" @click="remove(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <UiPagination :total="total" :page="page" :size="size" @change="onPage" />

    <UiModal v-model:visible="formVisible" :title="editingId == null ? '添加人员' : '编辑人员'" width="420px">
      <label class="f-label">员工编号</label>
      <input v-model="form.employeeId" class="input" style="width:100%" placeholder="员工编号" />
      <label class="f-label">姓名</label>
      <input v-model="form.name" class="input" style="width:100%" placeholder="姓名" />
      <label class="f-label">手机号</label>
      <input v-model="form.phone" class="input" type="tel" maxlength="11" style="width:100%" placeholder="11 位手机号" />
      <label class="f-label">部门</label>
      <input v-model="form.department" class="input" style="width:100%" placeholder="如：计算机系" />
      <template #footer>
        <button class="btn ghost" @click="formVisible = false">取消</button>
        <button class="btn primary" @click="submitForm">保存</button>
      </template>
    </UiModal>

    <UiModal v-model:visible="importVisible" title="导入花名册（员工编号 / 姓名 / 手机号 / 部门 四列）" width="420px">
      <input ref="fileInput" type="file" accept=".xlsx,.xls" />
      <p v-if="importError" class="import-error">{{ importError }}</p>
      <template #footer>
        <button class="btn ghost" @click="importVisible = false">取消</button>
        <button class="btn primary" :disabled="importing" @click="submitImport">导入</button>
      </template>
    </UiModal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import UiModal from '../components/ui/UiModal.vue'
import UiPagination from '../components/ui/UiPagination.vue'
import { confirm } from '../components/ui/confirm'
import { toast } from '../components/ui/toast'
import { http, type ApiError } from '../api/http'
import { downloadFile } from '../api/download'
import type { PageResult, PersonRow } from '../api/types'

const keyword = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const records = ref<PersonRow[]>([])
const formVisible = ref(false)
const form = ref({ employeeId: '', name: '', phone: '', department: '' })
const editingId = ref<number | null>(null)
const importVisible = ref(false)
const importError = ref('')
const importing = ref(false)
const fileInput = ref<HTMLInputElement>()

const PHONE = /^1[3-9]\d{9}$/

async function load() {
  const { data } = await http.get<PageResult<PersonRow>>('/api/roster', {
    params: { keyword: keyword.value, page: page.value, size },
  })
  total.value = data.total
  records.value = data.records
}

function search() {
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

function openAdd() {
  editingId.value = null
  form.value = { employeeId: '', name: '', phone: '', department: '' }
  formVisible.value = true
}

/** 校验通过返回 false 并提示（供添加/编辑共用） */
function validate(f: { employeeId: string; phone: string }): boolean {
  if (!f.employeeId.trim()) {
    toast.warning('请输入员工编号')
    return false
  }
  if (!PHONE.test(f.phone)) {
    toast.warning('手机号须为 11 位有效手机号')
    return false
  }
  return true
}

async function submitForm() {
  if (!validate(form.value)) return
  try {
    if (editingId.value == null) {
      await http.post('/api/roster', form.value)
      toast.success('已添加')
    } else {
      await http.put(`/api/roster/${editingId.value}`, form.value)
      toast.success('已保存')
    }
    formVisible.value = false
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

function startEdit(row: PersonRow) {
  editingId.value = row.id
  form.value = { employeeId: row.employeeId, name: row.name, phone: row.phone, department: row.department }
  formVisible.value = true
}

async function submitImport() {
  const file = fileInput.value?.files?.[0]
  if (!file) {
    importError.value = '请选择 xlsx 文件'
    return
  }
  importError.value = ''
  importing.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<{ imported: number }>('/api/roster/import', body)
    toast.success(`导入 ${data.imported} 人`)
    importVisible.value = false
    await load()
  } catch (e) {
    importError.value = (e as ApiError).message
  } finally {
    importing.value = false
  }
}

async function downloadTemplate() {
  await downloadFile('/api/roster/template', '花名册模板.xlsx')
}

/** 一键清空：双重确认；有报名组时后端 409，直接提示 message。 */
async function clearRoster() {
  try {
    await confirm('将清空当前活动全部花名册', '一键清空', 'warning')
    await confirm('再次确认：清空后不可恢复！', '一键清空', 'danger')
  } catch {
    return
  }
  try {
    const { data } = await http.delete<{ deleted: number }>('/api/roster')
    toast.success(`已清空 ${data.deleted} 人`)
    page.value = 1
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

async function remove(row: PersonRow) {
  try {
    await confirm(`删除 ${row.name}？已入组的成员将一并移除`, '删除人员', 'warning')
  } catch {
    return
  }
  try {
    await http.delete(`/api/roster/${row.id}`)
    toast.success('已删除')
    await load()
  } catch (e) {
    toast.error((e as ApiError).message)
  }
}

defineExpose({ keyword, page, total, records, form, formVisible, editingId,
  load, search, openAdd, submitForm, startEdit, clearRoster, remove })

load()
</script>

<style scoped>
.import-error { color: var(--red); font-size: 12.5px; margin-top: 10px; }
</style>
