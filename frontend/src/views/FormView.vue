<template>
  <div class="form-page">
    <van-nav-bar :title="info?.name ?? '报名'" />

    <template v-if="info">
      <!-- 活动不在进行中：占位 -->
      <van-empty v-if="info.windowStatus === 'NOT_STARTED'"
                 description="活动未开始，开始时间见现场通知" />
      <van-empty v-else-if="info.windowStatus === 'ENDED'" description="活动已结束，报名通道关闭" />

      <!-- 已提交：本组详情 -->
      <template v-else-if="team && !editing">
        <van-cell-group inset title="本组信息">
          <van-cell title="组名" :value="team.name" />
          <van-cell title="状态">
            <template #value>
              <van-tag :type="team.status === 'PENDING' ? 'warning'
                              : team.status === 'CONFIRMED' ? 'success' : 'danger'">
                {{ team.status === 'PENDING' ? '待审核' : team.status === 'CONFIRMED' ? '已通过' : '已驳回' }}
              </van-tag>
            </template>
          </van-cell>
          <van-cell v-if="team.status === 'REJECTED' && team.rejectReason" title="驳回理由"
                    :label="team.rejectReason" />
          <van-cell title="人数" :value="`${team.members.length} 人${team.overLimit ? '（超上限）' : ''}`" />
        </van-cell-group>
        <van-cell-group inset title="组员">
          <van-cell v-for="m in team.members" :key="m.phone"
                    :title="m.name" :value="m.phone" :label="m.department" />
        </van-cell-group>
        <div class="pad">
          <van-button type="primary" block @click="startEdit">修改组员（修改后需重新审核）</van-button>
        </div>
      </template>

      <!-- 建组表单 / 组长编辑 -->
      <template v-else>
        <van-notice-bar v-if="conflicts.length" wrapable :scrollable="false"
                        color="#ee0a24" background="#fff1f1"
                        :text="`以下成员已在其他组：${conflictText}`" />
        <van-notice-bar v-if="editing" wrapable :scrollable="false"
                        text="保存后将重新进入人工审核" />

        <van-cell-group inset title="组长信息">
          <van-field v-model="leaderPhone" type="tel" maxlength="11" label="组长手机号"
                     placeholder="输入完整 11 位手机号" @update:model-value="onLeaderPhone" />
          <van-cell v-if="leader" :title="leader.name" :label="leader.department" value="组长" />
          <van-cell v-if="leaderError" :title="leaderError" />
        </van-cell-group>

        <van-cell-group inset title="组员">
          <van-cell v-for="m in members" :key="m.phone" :title="`${m.name} ${m.phone}`"
                    :label="m.department" is-link @click="removeMember(m.phone)">
            <template #value><van-tag type="danger">移除</van-tag></template>
          </van-cell>
          <van-field v-model="addPhone" type="tel" maxlength="11" label="添加组员"
                     placeholder="输入完整 11 位手机号" @update:model-value="previewAdd" />
          <van-cell v-if="addPreview" :title="`${addPreview.name} ${addPreview.phone}`"
                    :label="addPreview.department">
            <template #value><van-tag type="primary" @click="addMember(addPhone)">加入</van-tag></template>
          </van-cell>
          <van-cell v-if="addError" :title="addError" />
        </van-cell-group>

        <van-notice-bar v-if="overLimit" wrapable :scrollable="false"
                        :text="`已超出上限 ${info.groupSizeLimit} 人，提交时将再次确认`" />
        <div class="pad">
          <van-button type="primary" block :loading="submitting" @click="onSubmitClick">
            {{ editing ? '保存修改' : '提交报名' }}（{{ members.length }} 人）
          </van-button>
        </div>
      </template>
    </template>
    <van-skeleton v-else :row="6" style="padding: 24px" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showFailToast } from 'vant'
import { useFormPage } from '../composables/useFormPage'

const route = useRoute()
const token = String(route.params.token ?? '')
const {
  info, leader, leaderPhone, leaderError, members, addPhone, addPreview, addError,
  team, conflicts, editing,
  load, onLeaderPhone, previewAdd, addMember, removeMember, submit, startEdit, saveEdit,
} = useFormPage(token)

const submitting = ref(false)

const conflictText = computed(() =>
  conflicts.value.map(c => `${c.name}(${c.phone})→${c.teamName}`).join('；'))
const overLimit = computed(() =>
  info.value !== null && members.value.length > info.value.groupSizeLimit)

async function onSubmitClick() {
  if (!info.value) return
  if (overLimit.value) {
    try {
      await showConfirmDialog({
        title: '超出人数上限',
        message: `当前 ${members.value.length} 人，上限 ${info.value.groupSizeLimit} 人，仍要提交吗？`,
      })
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    if (editing.value) {
      await saveEdit()
      showSuccessToast('已保存，等待重新审核')
    } else {
      await submit()
      showSuccessToast('已提交，等待审核')
    }
  } catch (e) {
    showFailToast((e as { message?: string })?.message ?? '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => { load().catch(() => {}) })
</script>

<style scoped>
.form-page { min-height: 100vh; background: #f7f8fa; padding-bottom: 24px; }
.pad { padding: 16px; }
</style>
