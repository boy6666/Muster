import { ref, type Ref } from 'vue'
import { http, type ApiError } from '../api/http'
import type { FormInfo, FormPersonView, FormTeamView, TeamDetail, ConflictView, TeamMemberView } from '../api/types'

export function useFormPage(token: string) {
  const info: Ref<FormInfo | null> = ref(null)
  const me: Ref<FormPersonView | null> = ref(null)
  const meError = ref('')
  const members: Ref<TeamMemberView[]> = ref([])
  const addEmployeeId = ref('')
  const addPreview: Ref<FormPersonView | null> = ref(null)
  const addError = ref('')
  const team: Ref<TeamDetail | null> = ref(null)
  const teamView: Ref<FormTeamView | null> = ref(null)
  const conflicts: Ref<ConflictView[]> = ref([])
  const editing = ref(false)
  const cap = ref('')

  /** 二维码 token 人人可见，组级操作必须带创建组时发放的 capToken（旧格式/无效记录直接清理）。 */
  function readStoredTeam(): { teamId: number; cap: string } | null {
    const raw = localStorage.getItem(`muster.team.${token}`)
    if (!raw) return null
    try {
      const parsed = JSON.parse(raw) as { teamId?: unknown; cap?: unknown }
      if (typeof parsed.teamId === 'number' && typeof parsed.cap === 'string') {
        return { teamId: parsed.teamId, cap: parsed.cap }
      }
    } catch { /* 旧格式或损坏数据，走清理 */ }
    localStorage.removeItem(`muster.team.${token}`)
    return null
  }

  function storeTeam(stored: { teamId: number; cap: string }): void {
    localStorage.setItem(`muster.team.${token}`, JSON.stringify(stored))
  }

  async function load() {
    const { data } = await http.get<FormInfo>(`/api/form/${token}`)
    info.value = data
    const saved = readStoredTeam()
    if (saved) {
      try {
        const detail = (await http.get<TeamDetail>(
          `/api/form/${token}/teams/${saved.teamId}?cap=${encodeURIComponent(saved.cap)}`)).data
        team.value = detail
        cap.value = saved.cap
      } catch { localStorage.removeItem(`muster.team.${token}`) }
    }
  }

  /** 按完整员工编号查身份；已在组时顺带拉"我的组"视图。 */
  async function lookupMe(employeeId: string): Promise<void> {
    meError.value = ''
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { employeeId } })
      me.value = data
      if (data.teamId != null) {
        teamView.value = (await http.get<FormTeamView>(
          `/api/form/${token}/my-team`, { params: { employeeId } })).data
      } else {
        teamView.value = null
      }
    } catch (e) {
      me.value = null
      teamView.value = null
      meError.value = (e as ApiError).message
    }
  }

  /** 以本人为组长开新组：成员列表首行即本人。 */
  function startCreate(): void {
    team.value = null
    conflicts.value = []
    editing.value = false
    members.value = me.value
      ? [{ employeeId: me.value.employeeId, name: me.value.name, phone: me.value.phone,
           department: me.value.department, isLeader: true }]
      : []
  }

  async function previewAdd(): Promise<void> {
    addError.value = ''
    addPreview.value = null
    const employeeId = addEmployeeId.value.trim()
    if (!employeeId) return
    if (members.value.some(m => m.employeeId === employeeId)) {
      addError.value = '该成员已在本组'
      return
    }
    try {
      addPreview.value = (await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { employeeId } })).data
    } catch (e) {
      addError.value = (e as ApiError).message
    }
  }

  async function addMember(employeeId: string): Promise<boolean> {
    const id = employeeId.trim()
    if (members.value.some(m => m.employeeId === id)) return false
    if (!id) { addError.value = '请输入员工编号'; return false }
    try {
      const { data } = await http.get<FormPersonView>(
        `/api/form/${token}/person`, { params: { employeeId: id } })
      members.value.push({ employeeId: data.employeeId, name: data.name, phone: data.phone,
        department: data.department, isLeader: false })
      addEmployeeId.value = ''
      addPreview.value = null
      return true
    } catch (e) {
      addError.value = (e as ApiError).message
      return false
    }
  }

  function removeMember(employeeId: string): void {
    members.value = members.value.filter(m => m.employeeId !== employeeId)
  }

  function handleConflict(e: unknown): void {
    const apiError = e as ApiError
    if (apiError.code === 'CONFLICT' && Array.isArray(apiError.data)) {
      conflicts.value = apiError.data as ConflictView[]
    } else {
      throw e
    }
  }

  async function createDraft(): Promise<void> {
    conflicts.value = []
    try {
      const { data } = await http.post<TeamDetail>(`/api/form/${token}/teams`, {
        leaderEmployeeId: members.value[0]?.employeeId ?? '',
        memberEmployeeIdList: members.value.map(m => m.employeeId),
      })
      team.value = data
      cap.value = data.capToken
      storeTeam({ teamId: data.id, cap: data.capToken })
    } catch (e) {
      handleConflict(e)
    }
  }

  /** 提交审核：首提必须带组长手机号；重提交可只带 cap。 */
  async function submit(leaderPhone: string): Promise<void> {
    const target = team.value ?? { id: teamView.value!.id }
    const query = cap.value ? `?cap=${encodeURIComponent(cap.value)}` : ''
    const { data } = await http.post<TeamDetail>(
      `/api/form/${token}/teams/${target.id}/submit${query}`, { leaderPhone })
    if (team.value) team.value = data
    if (teamView.value) teamView.value = { ...teamView.value, status: data.status, rejectReason: data.rejectReason }
  }

  /** 保存：只换成员，不改状态。组长以现组长为准（组长转让时调用方先改 members[0]）。 */
  async function save(): Promise<void> {
    const target = team.value ?? { id: teamView.value!.id }
    const leaderEmployeeId = team.value?.members.find(m => m.isLeader)?.employeeId
      ?? members.value[0]?.employeeId ?? ''
    const { data } = await http.put<TeamDetail>(
      `/api/form/${token}/teams/${target.id}?cap=${encodeURIComponent(cap.value)}`, {
        leaderEmployeeId,
        memberEmployeeIdList: members.value.map(m => m.employeeId),
      })
    if (team.value) team.value = data
    editing.value = false
  }

  /** 换机验证：组长凭手机号换 capToken。 */
  async function verify(teamId: number, leaderPhone: string): Promise<void> {
    const { data } = await http.post<TeamDetail>(`/api/form/${token}/teams/${teamId}/verify`, { leaderPhone })
    cap.value = data.capToken
    storeTeam({ teamId, cap: data.capToken })
    team.value = data
  }

  async function deleteTeam(): Promise<void> {
    const target = team.value ?? { id: teamView.value!.id }
    await http.delete(`/api/form/${token}/teams/${target.id}?cap=${encodeURIComponent(cap.value)}`)
    localStorage.removeItem(`muster.team.${token}`)
    team.value = null
    teamView.value = null
    me.value = null
    members.value = []
    editing.value = false
    cap.value = ''
  }

  function startEdit(): void {
    if (!team.value) return
    members.value = [...team.value.members]
    editing.value = true
  }

  function cancelEdit(): void {
    editing.value = false
    addPreview.value = null
    addError.value = ''
  }

  async function reloadTeam(): Promise<void> {
    if (!team.value) return
    team.value = (await http.get<TeamDetail>(
      `/api/form/${token}/teams/${team.value.id}?cap=${encodeURIComponent(cap.value)}`)).data
  }

  return {
    info, me, meError, members, addEmployeeId, addPreview, addError,
    team, teamView, conflicts, editing, cap,
    load, lookupMe, startCreate, previewAdd, addMember, removeMember,
    createDraft, submit, save, verify, deleteTeam, startEdit, cancelEdit, reloadTeam,
  }
}
