export interface ActivityResponse {
  id: number
  name: string
  startTime: string
  endTime: string
  groupSizeLimit: number
  qrToken: string
  exported: boolean
  manuallyEnded: boolean
  windowStatus: 'NOT_STARTED' | 'ACTIVE' | 'ENDED'
}
export interface Stats {
  total: number
  joined: number
  notJoined: number
  teamCount: number
  pendingTeamCount: number
}
export interface FormInfo {
  name: string
  startTime: string
  endTime: string
  groupSizeLimit: number
  windowStatus: 'NOT_STARTED' | 'ACTIVE' | 'ENDED'
}
export interface TeamMemberView { name: string; phone: string; department: string }
export interface TeamDetail {
  id: number
  name: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  rejectReason: string | null
  capToken: string | null
  overLimit: boolean
  submittedAt: string
  members: TeamMemberView[]
}
export interface TeamAdminResponse {
  id: number
  name: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  size: number
  overLimit: boolean
  rejectReason: string | null
  submittedAt: string
}
export interface FormPersonView { name: string; phone: string; department: string }
export interface ConflictView { phone: string; name: string; teamName: string }
export interface PageResult<T> { total: number; records: T[] }
export interface OpLogView {
  id: number
  adminUsername: string
  action: string
  detail: string | null
  createdAt: string
}
export interface TeamEventView {
  id: number
  type: 'SUBMITTED' | 'EDITED_BY_LEADER' | 'EDITED_BY_ADMIN' | 'PASSED' | 'REJECTED'
  detail: string | null
  createdAt: string
}
