import { ref, type Ref } from 'vue'
import { http, type ApiError } from '../api/http'
import type { FormInfo, FormPersonView, TeamDetail, ConflictView } from '../api/types'

interface Member { name: string; phone: string; department: string }

export function useFormPage(token: string) {
  const info: Ref<FormInfo | null> = ref(null)
  const leader: Ref<Member | null> = ref(null)
  const leaderPhone = ref('')
  const leaderError = ref('')
  const members: Ref<Member[]> = ref([])
  const addPhone = ref('')
  const addPreview: Ref<Member | null> = ref(null)
  const addError = ref('')
  const team: Ref<TeamDetail | null> = ref(null)
  const conflicts: Ref<ConflictView[]> = ref([])
  const editing = ref(false)

  const PHONE = /^1[3-9]\d{9}$/

  async function load() {
    const { data } = await http.get<FormInfo>(`/api/form/${token}`)
    info.value = data
    const saved = localStorage.getItem(`muster.team.${token}`)
    if (saved) {
      try { team.value = (await http.get<TeamDetail>(`/api/form/${token}/teams/${saved}`)).data }
      catch { localStorage.removeItem(`muster.team.${token}`) }
    }
  }

  /** 输入完整 11 位才回显；查到即作为组长自动加入成员列表首位。 */
  async function onLeaderPhone(phone: string): Promise<void> {
    leaderError.value = ''
    if (!PHONE.test(phone)) return
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone } })
      leader.value = data
      if (!members.value.some(m => m.phone === data.phone)) {
        members.value.unshift(data)
      }
    } catch (e) {
      leader.value = null
      leaderError.value = (e as ApiError).message
    }
  }

  async function previewAdd(): Promise<void> {
    addError.value = ''
    addPreview.value = null
    if (!PHONE.test(addPhone.value)) return
    if (members.value.some(m => m.phone === addPhone.value)) {
      addError.value = '该成员已在本组'
      return
    }
    try {
      addPreview.value = (await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone: addPhone.value } })).data
    } catch (e) {
      addError.value = (e as ApiError).message
    }
  }

  async function addMember(phone: string): Promise<boolean> {
    if (members.value.some(m => m.phone === phone)) return false
    if (!PHONE.test(phone)) { addError.value = '请输入完整 11 位手机号'; return false }
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { phone } })
      members.value.push(data)
      addPhone.value = ''
      addPreview.value = null
      return true
    } catch (e) {
      addError.value = (e as ApiError).message
      return false
    }
  }

  function removeMember(phone: string): void {
    members.value = members.value.filter(m => m.phone !== phone)
    if (leader.value?.phone === phone) leader.value = null
  }

  /** 超上限由模板弹 confirm；本函数专注提交与结果处理。 */
  async function submit(): Promise<void> {
    conflicts.value = []
    try {
      const resp = await http.post<TeamDetail>(`/api/form/${token}/teams`,
        { memberPhoneList: members.value.map(m => m.phone) })
      team.value = resp.data
      localStorage.setItem(`muster.team.${token}`, String(resp.data.id))
      editing.value = false
    } catch (e) {
      const apiError = e as ApiError
      if (apiError.code === 'CONFLICT' && Array.isArray(apiError.data)) {
        conflicts.value = apiError.data as ConflictView[]
      } else {
        throw e
      }
    }
  }

  async function startEdit(): Promise<void> {
    if (!team.value) return
    members.value = [...team.value.members]
    leader.value = team.value.members[0] ?? null
    editing.value = true
  }

  async function saveEdit(): Promise<void> {
    const resp = await http.put<TeamDetail>(`/api/form/${token}/teams/${team.value!.id}`,
      { memberPhoneList: members.value.map(m => m.phone) })
    team.value = resp.data
    editing.value = false
  }

  async function reloadTeam(): Promise<void> {
    if (!team.value) return
    team.value = (await http.get<TeamDetail>(`/api/form/${token}/teams/${team.value.id}`)).data
  }

  return {
    info, leader, leaderPhone, leaderError, members, addPhone, addPreview, addError,
    team, conflicts, editing,
    load, onLeaderPhone, previewAdd, addMember, removeMember, submit, startEdit, saveEdit, reloadTeam,
  }
}
