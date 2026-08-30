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
  registered: number
  notRegistered: number
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
export type TeamStatus = 'DRAFT' | 'PENDING' | 'CONFIRMED' | 'REJECTED'
export interface TeamMemberView {
  employeeId: string
  name: string
  phone: string
  department: string
  isLeader: boolean
}
export interface TeamDetail {
  id: number
  name: string
  status: TeamStatus
  rejectReason: string | null
  capToken: string
  overLimit: boolean
  submittedAt: string | null
  members: TeamMemberView[]
}
export interface TeamAdminResponse {
  id: number
  name: string
  status: TeamStatus
  size: number
  overLimit: boolean
  leaderName: string
  rejectReason: string | null
  submittedAt: string | null
}
export interface FormPersonView {
  employeeId: string
  name: string
  phone: string
  department: string
  teamId: number | null
  leader: boolean
}
export interface FormTeamView {
  id: number
  name: string
  status: TeamStatus
  rejectReason: string | null
  overLimit: boolean
  submittedAt: string | null
  isLeader: boolean
  members: TeamMemberView[]
}
export interface ConflictView { employeeId: string; name: string; teamName: string }
export interface PageResult<T> { total: number; records: T[] }
export interface OpLogView {
  id: number
  adminUsername: string
  action: string
  detail: string | null
  createdAt: string
}
export type TeamEventType =
  | 'CREATED' | 'SAVED' | 'SUBMITTED' | 'EDITED_BY_ADMIN'
  | 'CREATED_BY_ADMIN' | 'PASSED' | 'REJECTED'
export interface TeamEventView {
  id: number
  type: TeamEventType
  detail: string | null
  createdAt: string
}
export interface PersonRow {
  id: number
  employeeId: string
  name: string
  phone: string
  department: string
  teamId: number | null
  teamName: string | null
  leaderName: string | null
  isLeader: boolean
  participated: boolean
}
