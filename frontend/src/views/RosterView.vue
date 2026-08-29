<template>
  <div>
    <el-space style="margin-bottom:12px" wrap>
      <el-input v-model="keyword" placeholder="姓名 / 手机号 / 部门" style="width:240px" clearable
                @keyup.enter="search" @clear="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="downloadTemplate">下载模板</el-button>
      <el-button type="primary" @click="importVisible = true">导入 Excel</el-button>
      <el-button @click="openAdd">添加人员</el-button>
    </el-space>

    <el-table :data="records" border>
      <el-table-column prop="name" label="姓名" width="140" />
      <el-table-column prop="phone" label="手机号" width="150" />
      <el-table-column prop="department" label="部门" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   @current-change="onPage" />

    <el-dialog v-model="addVisible" title="添加人员" width="420px">
      <el-form label-width="80px">
        <el-form-item label="姓名">
          <el-input v-model="addForm.name" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="addForm.phone" type="tel" maxlength="11" />
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="addForm.department" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAdd">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入花名册（姓名 / 手机号 / 部门 三列）" width="420px">
      <input ref="fileInput" type="file" accept=".xlsx,.xls" />
      <p v-if="importError" class="error">{{ importError }}</p>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type ApiError } from '../api/http'
import { downloadFile } from '../api/download'
import type { PageResult } from '../api/types'

interface PersonRow { id: number; name: string; phone: string; department: string }

const keyword = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const records = ref<PersonRow[]>([])
const addVisible = ref(false)
const addForm = ref({ name: '', phone: '', department: '' })
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
  addForm.value = { name: '', phone: '', department: '' }
  addVisible.value = true
}

async function submitAdd() {
  if (!PHONE.test(addForm.value.phone)) {
    ElMessage.warning('手机号须为 11 位有效手机号')
    return
  }
  try {
    await http.post('/api/roster', addForm.value)
    ElMessage.success('已添加')
    addVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
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
    const { data } = await http.post<number>('/api/roster/import', body)
    ElMessage.success(`导入 ${data} 人`)
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

async function remove(row: PersonRow) {
  try {
    await ElMessageBox.confirm(`删除 ${row.name}？已入组的成员将一并移除`, '删除人员', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/api/roster/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

defineExpose({ keyword, page, total, records, addForm, addVisible, load, search, submitAdd })

load()
</script>

<style scoped>
.error { color: var(--el-color-danger); }
</style>
