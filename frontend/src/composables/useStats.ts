import { ref, type Ref } from 'vue'
import { http, getToken } from '../api/http'
import type { Stats } from '../api/types'

const stats: Ref<Stats | null> = ref(null)
let socket: WebSocket | null = null
let users = 0
let retryTimer: ReturnType<typeof setTimeout> | null = null
let factory: (url: string) => WebSocket = url => new WebSocket(url)

/** 测试注入假 WebSocket 用。 */
export function setSocketFactory(f: (url: string) => WebSocket): void {
  factory = f
}

function connect() {
  if (socket) return
  socket = factory(`/ws/stats?token=${encodeURIComponent(getToken() ?? '')}`)
  socket.onmessage = ev => {
    try { stats.value = JSON.parse(String(ev.data)) } catch { /* 忽略坏帧 */ }
  }
  socket.onclose = () => {
    socket = null
    if (users > 0 && retryTimer === null) {
      retryTimer = setTimeout(() => { retryTimer = null; connect() }, 3000)
    }
  }
}

export function useStats() {
  users++
  async function refresh() {
    const { data } = await http.get<Stats>('/api/stats')
    stats.value = data
  }
  function start() {
    refresh().catch(() => { /* WS 帧会补上 */ })
    connect()
  }
  function stop() {
    users--
    if (users === 0) {
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
      socket?.close()
      socket = null
    }
  }
  return { stats, start, stop, refresh }
}
