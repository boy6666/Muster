<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import UiModal from '../components/ui/UiModal.vue'
import { confirm } from '../components/ui/confirm'
import { toast } from '../components/ui/toast'
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
const STATUS_CLASS: Record<TeamStatus, string> =
  { DRAFT: 'info', PENDING: 'warn', CONFIRMED: 'ok', REJECTED: 'err' }
const statusText = (s: TeamStatus) => STATUS_TEXT[s]
const statusClass = (s: TeamStatus) => STATUS_CLASS[s]

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
    if (!conflicts.value.length) toast.success(editing.value ? '已保存' : '已保存草稿')
  } catch (e) {
    toast.error((e as { message?: string }).message ?? '保存失败')
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
    toast.success('已提交，等待审核')
  } catch (e) {
    toast.error((e as { message?: string }).message ?? '提交失败')
  } finally {
    busy.value = false
  }
}

/** 换机验证：组长凭手机号取回 capToken。 */
async function doVerify(): Promise<void> {
  if (!PHONE.test(verifyPhoneInput.value)) {
    toast.error('请输入组长的 11 位手机号')
    return
  }
  const target = team.value ?? teamView.value
  if (!target) return
  busy.value = true
  try {
    await verify(target.id, verifyPhoneInput.value)
    toast.success('验证成功')
  } catch (e) {
    toast.error((e as { message?: string }).message ?? '验证失败')
  } finally {
    busy.value = false
  }
}

async function onDelete(): Promise<void> {
  try {
    await confirm('删除后组员回到未报名状态，确认删除？', '删除本组', 'danger')
  } catch {
    return
  }
  busy.value = true
  try {
    await deleteTeam()
    toast.success('已删除')
  } catch (e) {
    toast.error((e as { message?: string }).message ?? '删除失败')
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
    <div class="p-nav">{{ info?.name ?? '报名' }} · 分组报名</div>

    <div class="p-body">
      <template v-if="info">
        <!-- 窗口空态 -->
        <div v-if="info.windowStatus === 'NOT_STARTED'" class="p-group">
          <div class="p-cell">活动未开始，开始时间见现场通知</div>
        </div>
        <div v-else-if="info.windowStatus === 'ENDED'" class="p-group">
          <div class="p-cell">活动已结束，报名通道关闭</div>
        </div>

        <template v-else>
          <!-- 查看本组（DRAFT/PENDING/CONFIRMED/REJECTED） -->
          <template v-if="view && !editing">
            <div class="p-group">
              <div class="p-gt">我的组 · MY TEAM</div>
              <div class="p-cell">
                <span class="lbl">组名</span>
                <span class="val"><b>{{ view.name }}</b></span>
              </div>
              <div class="p-cell">
                <span class="lbl">状态</span>
                <span class="val"></span>
                <span :class="`p-tag ${statusClass(view.status)}`">{{ statusText(view.status) }}</span>
              </div>
            </div>
            <div v-if="view.status === 'REJECTED' && view.rejectReason" class="alert err">
              驳回理由：{{ view.rejectReason }}
            </div>

            <div class="p-group">
              <div class="p-gt">组员 · MEMBERS ({{ view.members.length }})</div>
              <div v-for="m in view.members" :key="m.employeeId" class="p-cell">
                <span class="val"><b>{{ m.name }} {{ m.employeeId }}</b>
                  <div class="sub">{{ m.phone }} · {{ m.department }}</div>
                </span>
                <span v-if="m.isLeader" class="p-tag info">组长</span>
              </div>
            </div>

            <div v-if="view.status === 'PENDING'" class="p-notice warn">审核中，不能修改或删除</div>

            <template v-if="manageable">
              <!-- 无 cap：换机验证 -->
              <template v-if="!cap">
                <div class="p-group">
                  <div class="p-gt">换机验证 · VERIFY</div>
                  <div class="p-cell">
                    <span class="val">首次在本设备操作，输入组长手机号验证后即可修改或删除本组</span>
                  </div>
                  <div class="p-cell">
                    <span class="lbl">组长手机号</span>
                    <input v-model="verifyPhoneInput" class="p-field" type="tel" maxlength="11"
                           placeholder="输入完整 11 位手机号">
                  </div>
                </div>
                <button class="p-btn" data-test="verify-submit" :disabled="busy" @click="doVerify">验证</button>
              </template>
              <template v-else>
                <button class="p-btn secondary" data-test="edit-team" :disabled="busy" @click="startEdit">
                  修改组员
                </button>
                <button v-if="view.status === 'REJECTED'" class="p-btn" data-test="resubmit" :disabled="busy"
                        @click="onSubmitClick">
                  提交报名
                </button>
                <button class="p-btn danger" data-test="delete-team" :disabled="busy" @click="onDelete">
                  删除本组
                </button>
              </template>
            </template>
          </template>

          <!-- 编辑组员 / 新建组 -->
          <template v-else-if="editing || me">
            <div v-if="conflicts.length" class="alert err">以下成员已在其他组：{{ conflictText }}</div>

            <div v-if="me" class="p-group">
              <div class="p-gt">我的身份 · ME</div>
              <div class="p-cell">
                <span class="val"><b>{{ me.name }}</b>
                  <div class="sub">{{ me.employeeId }} · {{ me.department }}</div>
                </span>
                <span class="p-tag info">{{ me.phone }}</span>
              </div>
            </div>

            <div class="p-group">
              <div class="p-gt">组员 · MEMBERS ({{ members.length }})</div>
              <div v-for="(m, i) in members" :key="m.employeeId" class="p-cell">
                <span class="val"><b>{{ m.name }} {{ m.employeeId }}</b>
                  <div class="sub">{{ m.phone }} · {{ m.department }}</div>
                </span>
                <span v-if="i === 0" class="p-tag info">组长</span>
                <button v-else class="p-rm" @click="removeMember(m.employeeId)">移除</button>
              </div>
              <div class="p-cell">
                <span class="lbl">添加组员</span>
                <input v-model="addEmployeeId" class="p-field" placeholder="输入完整员工编号" @input="onAddInput()">
              </div>
              <div v-if="addPreview" class="p-cell">
                <span class="val"><b>{{ addPreview.name }} {{ addPreview.employeeId }}</b>
                  <div class="sub">{{ addPreview.phone }} · {{ addPreview.department }}</div>
                </span>
                <button class="p-tag info tag-btn" @click="confirmAdd">＋ 加入</button>
              </div>
            </div>
            <div v-if="addError" class="p-notice err">{{ addError }}</div>

            <div v-if="overLimit" class="p-notice err">已超出上限 {{ info.groupSizeLimit }} 人，仍可提交</div>
            <div v-else-if="members.length > 0 && members.length < info.groupSizeLimit" class="p-notice warn">
              少于上限 {{ info.groupSizeLimit }} 人
            </div>

            <button class="p-btn secondary" data-test="save-draft" :disabled="busy" @click="onSaveDraft">
              {{ editing ? '保存修改' : '保存草稿' }}
            </button>
            <button class="p-btn" data-test="submit" :disabled="busy" @click="onSubmitClick">提交报名</button>
            <button v-if="editing" class="p-btn ghost" data-test="cancel-edit" :disabled="busy" @click="cancelEdit">
              取消
            </button>
          </template>

          <!-- 身份识别 -->
          <template v-else>
            <div class="p-group">
              <div class="p-gt">身份识别 · IDENTITY</div>
              <div class="p-cell">
                <span class="lbl">员工编号</span>
                <input v-model="employeeIdInput" class="p-field" placeholder="请输入完整员工编号"
                       @input="onEmployeeIdInput(($event.target as HTMLInputElement).value)">
              </div>
            </div>
            <div v-if="meError" class="p-notice err">{{ meError }}</div>
          </template>
        </template>
      </template>

      <div v-else class="p-group">
        <div class="p-cell">加载中…</div>
      </div>
    </div>

    <!-- 首次提交：组长手机号验证 -->
    <UiModal v-model:visible="phoneDialog" title="组长手机验证" width="360px" modal-class="modal-phone">
      <div class="p-cell modal-row">
        <span class="lbl">组长手机号</span>
        <input v-model="dialogPhone" class="input" type="tel" maxlength="11" placeholder="输入完整 11 位手机号">
      </div>
      <div v-if="phoneError" class="p-notice err modal-row">{{ phoneError }}</div>
      <template #footer>
        <button class="btn ghost" @click="phoneDialog = false">取消</button>
        <button class="btn primary" data-test="phone-confirm" :disabled="busy" @click="confirmPhone">确认</button>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
/* p-btn 状态变体：tokens.css 只有基础 .p-btn（渐变主色），此处按原型补次级/危险/幽灵态 */
.p-btn.secondary {
  background: rgba(6, 182, 212, .08);
  color: var(--cyan);
  border: 1px solid var(--line-strong);
  box-shadow: none;
}

.p-btn.danger {
  background: #ffffff;
  color: #be123c;
  border: 1px solid rgba(225, 29, 72, .35);
  box-shadow: none;
}

.p-btn.ghost {
  background: transparent;
  color: var(--text-2);
  border: 1px solid var(--line);
  box-shadow: none;
}

.p-btn:disabled {
  opacity: .55;
  cursor: not-allowed;
  box-shadow: none;
}

/* p-tag / p-rm 用在 button 上时清掉原生按钮底色 */
button.p-tag,
button.p-rm {
  background: transparent;
  cursor: pointer;
  font-family: var(--sans);
}

.modal-row {
  padding: 0 0 10px;
  border-top: none;
}

.modal-row .input {
  flex: 1;
}
</style>
