<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant'
import { useFormPage } from '../composables/useFormPage'
import type { TeamStatus } from '../api/types'

const route = useRoute()
const token = String(route.params.token ?? '')
const page = useFormPage(token)
const {
  info, me, meError, members, addEmployeeId, addPreview, addError,
  team, teamView, conflicts, editing, cap,
  load, lookupMe, startCreate, previewAdd, addMember, removeMember,
  createDraft, submit, save, verify, deleteTeam, startEdit, cancelEdit,
} = page

const employeeIdInput = ref('')
const verifyPhoneInput = ref('')
const dialogPhone = ref('')
const phoneDialog = ref(false)
const phoneError = ref('')
const busy = ref(false)

const PHONE = /^1[3-9]\d{9}$/

/** 展示中的组：本地有 cap 的详情优先，否则用"我的组"视图。 */
const view = computed(() => team.value ?? teamView.value)
/** 本地 cap 即组长凭证；my-team 视图则看 isLeader。可管理的状态：DRAFT / REJECTED。 */
const manageable = computed(() => {
  const v = view.value
  if (!v) return false
  const leader = 'isLeader' in v ? v.isLeader : true
  return leader && (v.status === 'DRAFT' || v.status === 'REJECTED')
})
const overLimit = computed(() => info.value !== null && members.value.length > info.value.groupSizeLimit)

const STATUS_TEXT: Record<TeamStatus, string> =
  { DRAFT: '草稿', PENDING: '审核中', CONFIRMED: '已通过', REJECTED: '已驳回' }
const STATUS_TYPE: Record<TeamStatus, 'primary' | 'warning' | 'success' | 'danger'> =
  { DRAFT: 'primary', PENDING: 'warning', CONFIRMED: 'success', REJECTED: 'danger' }
const statusText = (s: TeamStatus) => STATUS_TEXT[s]
const statusType = (s: TeamStatus) => STATUS_TYPE[s]

const conflictText = computed(() =>
  conflicts.value.map(c => `${c.name}(${c.employeeId})→${c.teamName}`).join('；'))

let lookupTimer: ReturnType<typeof setTimeout> | undefined
/** 表单页不做模糊搜索：输入完整员工编号后防抖 400ms 再查。 */
function onEmployeeIdInput(value: string): void {
  clearTimeout(lookupTimer)
  const id = value.trim()
  if (!id) return
  lookupTimer = setTimeout(() => { void identify(id) }, 400)
}

/** 识别身份；未在组的人直接进入建组流程（首行本人=组长）。 */
async function identify(employeeId: string): Promise<void> {
  await lookupMe(employeeId)
  if (me.value && me.value.teamId == null) startCreate()
}

let addTimer: ReturnType<typeof setTimeout> | undefined
function onAddInput(): void {
  clearTimeout(addTimer)
  if (!addEmployeeId.value.trim()) {
    addPreview.value = null
    return
  }
  addTimer = setTimeout(() => { void previewAdd() }, 400)
}

function confirmAdd(): void {
  void addMember(addEmployeeId.value)
}

async function onSaveDraft(): Promise<void> {
  busy.value = true
  try {
    if (editing.value) await save()
    else await createDraft()
    if (!conflicts.value.length) showSuccessToast(editing.value ? '已保存' : '已保存草稿')
  } catch (e) {
    showFailToast((e as { message?: string }).message ?? '保存失败')
  } finally {
    busy.value = false
  }
}

/** 提交入口：首次提交（无 cap 或从未提交过）必须先验证组长手机号。 */
function onSubmitClick(): void {
  if (!cap.value || !team.value || team.value.submittedAt == null) {
    phoneError.value = ''
    phoneDialog.value = true
    return
  }
  void doSubmit('')
}

async function confirmPhone(): Promise<void> {
  if (!PHONE.test(dialogPhone.value)) {
    phoneError.value = '请输入组长的 11 位手机号'
    return
  }
  await doSubmit(dialogPhone.value)
}

async function doSubmit(phone: string): Promise<void> {
  busy.value = true
  try {
    if (editing.value) {
      await save()
    } else if (!team.value) {
      await createDraft()
      if (conflicts.value.length) {
        phoneDialog.value = false
        return
      }
    }
    await submit(phone)
    phoneDialog.value = false
    dialogPhone.value = ''
    showSuccessToast('已提交，等待审核')
  } catch (e) {
    showFailToast((e as { message?: string }).message ?? '提交失败')
  } finally {
    busy.value = false
  }
}

/** 换机验证：组长凭手机号取回 capToken。 */
async function doVerify(): Promise<void> {
  if (!PHONE.test(verifyPhoneInput.value)) {
    showFailToast('请输入组长的 11 位手机号')
    return
  }
  const target = team.value ?? teamView.value
  if (!target) return
  busy.value = true
  try {
    await verify(target.id, verifyPhoneInput.value)
    showSuccessToast('验证成功')
  } catch (e) {
    showFailToast((e as { message?: string }).message ?? '验证失败')
  } finally {
    busy.value = false
  }
}

async function onDelete(): Promise<void> {
  try {
    await showConfirmDialog({ title: '删除本组', message: '删除后组员回到未报名状态，确认删除？' })
  } catch {
    return
  }
  busy.value = true
  try {
    await deleteTeam()
    showSuccessToast('已删除')
  } catch (e) {
    showFailToast((e as { message?: string }).message ?? '删除失败')
  } finally {
    busy.value = false
  }
}

onMounted(() => { load().catch(() => {}) })

defineExpose({
  employeeIdInput, verifyPhoneInput, dialogPhone, phoneDialog, phoneError,
  onSaveDraft, onSubmitClick, confirmPhone, doVerify, onDelete, confirmAdd, identify,
  ...page,
})
</script>

<template>
  <div class="form-page">
    <van-nav-bar :title="info?.name ?? '报名'" />

    <template v-if="info">
      <van-empty v-if="info.windowStatus === 'NOT_STARTED'" description="活动未开始，开始时间见现场通知" />
      <van-empty v-else-if="info.windowStatus === 'ENDED'" description="活动已结束，报名通道关闭" />

      <template v-else>
        <!-- 查看本组（DRAFT/PENDING/CONFIRMED/REJECTED） -->
        <template v-if="view && !editing">
          <van-cell-group inset title="我的组">
            <van-cell title="组名" :value="view.name" />
            <van-cell title="状态">
              <template #value>
                <van-tag :type="statusType(view.status)">{{ statusText(view.status) }}</van-tag>
              </template>
            </van-cell>
            <van-cell v-if="view.status === 'REJECTED' && view.rejectReason" title="驳回理由" :label="view.rejectReason" />
          </van-cell-group>

          <van-cell-group inset title="组员">
            <van-cell v-for="m in view.members" :key="m.employeeId"
                      :title="`${m.name} ${m.employeeId}`" :label="`${m.phone} · ${m.department}`">
              <template #value>
                <van-tag v-if="m.isLeader" type="primary">组长</van-tag>
              </template>
            </van-cell>
          </van-cell-group>

          <van-notice-bar v-if="view.status === 'PENDING'" wrapable :scrollable="false"
                          text="审核中，不能修改或删除" />

          <template v-if="manageable">
            <!-- 无 cap：换机验证 -->
            <van-cell-group v-if="!cap" inset title="换机验证">
              <van-cell title="首次在本设备操作" label="输入组长手机号验证后即可修改或删除本组" />
              <van-field v-model="verifyPhoneInput" type="tel" maxlength="11" label="组长手机号"
                         placeholder="输入完整 11 位手机号" />
              <div class="pad">
                <van-button type="warning" block data-test="verify-submit" :loading="busy" @click="doVerify">
                  验证
                </van-button>
              </div>
            </van-cell-group>
            <div v-else class="pad">
              <van-button data-test="edit-team" block :loading="busy" @click="startEdit">修改组员</van-button>
              <van-button v-if="view.status === 'REJECTED'" data-test="resubmit" type="primary" block
                          :loading="busy" @click="onSubmitClick">提交报名</van-button>
              <van-button data-test="delete-team" type="danger" block :loading="busy" @click="onDelete">
                删除本组
              </van-button>
            </div>
          </template>
        </template>

        <!-- 编辑组员 / 新建组 -->
        <template v-else-if="editing || me">
          <van-notice-bar v-if="conflicts.length" wrapable :scrollable="false" color="#ee0a24" background="#fff1f1"
                          :text="`以下成员已在其他组：${conflictText}`" />

          <van-cell-group v-if="me" inset title="我的身份">
            <van-cell :title="me.name" :label="`${me.employeeId} · ${me.department}`" :value="me.phone" />
          </van-cell-group>

          <van-cell-group inset title="组员">
            <van-cell v-for="(m, i) in members" :key="m.employeeId"
                      :title="`${m.name} ${m.employeeId}`" :label="`${m.phone} · ${m.department}`">
              <template #value>
                <van-tag v-if="i === 0" type="primary">组长</van-tag>
                <van-tag v-else type="danger" @click="removeMember(m.employeeId)">移除</van-tag>
              </template>
            </van-cell>
            <van-field v-model="addEmployeeId" label="添加组员" placeholder="输入完整员工编号"
                       @update:model-value="onAddInput" />
            <van-cell v-if="addPreview" :title="`${addPreview.name} ${addPreview.employeeId}`"
                      :label="`${addPreview.phone} · ${addPreview.department}`">
              <template #value>
                <van-tag type="primary" @click="confirmAdd">加入</van-tag>
              </template>
            </van-cell>
            <van-cell v-if="addError" :title="addError" />
          </van-cell-group>

          <van-notice-bar v-if="overLimit" wrapable :scrollable="false" color="#ee0a24" background="#fff1f1"
                          :text="`已超出上限 ${info.groupSizeLimit} 人，仍可提交`" />
          <van-notice-bar v-else-if="members.length > 0 && members.length < info.groupSizeLimit"
                          wrapable :scrollable="false" :text="`少于上限 ${info.groupSizeLimit} 人`" />

          <div class="pad">
            <van-button data-test="save-draft" block :loading="busy" @click="onSaveDraft">
              {{ editing ? '保存修改' : '保存草稿' }}
            </van-button>
            <van-button data-test="submit" type="primary" block :loading="busy" @click="onSubmitClick">
              提交报名
            </van-button>
            <van-button v-if="editing" data-test="cancel-edit" block @click="cancelEdit">取消</van-button>
          </div>
        </template>

        <!-- 身份识别 -->
        <van-cell-group v-else inset title="身份识别">
          <van-field v-model="employeeIdInput" label="员工编号" placeholder="请输入完整员工编号"
                     @update:model-value="onEmployeeIdInput" />
          <van-cell v-if="meError" :title="meError" />
        </van-cell-group>
      </template>
    </template>
    <van-skeleton v-else :row="6" style="padding: 24px" />

    <!-- 首次提交：组长手机号验证 -->
    <van-popup v-model:show="phoneDialog" round style="padding: 24px">
      <div class="dialog-title">验证组长手机号</div>
      <van-field v-model="dialogPhone" type="tel" maxlength="11" label="组长手机号"
                 placeholder="输入完整 11 位手机号" />
      <div v-if="phoneError" class="phone-error">{{ phoneError }}</div>
      <van-button type="primary" block data-test="phone-confirm" :loading="busy" @click="confirmPhone">
        确认
      </van-button>
    </van-popup>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dialog-title {
  text-align: center;
  font-weight: 600;
  margin-bottom: 16px;
}

.phone-error {
  color: #ee0a24;
  font-size: 12px;
  padding: 4px 16px;
}
</style>
