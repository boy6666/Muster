import { describe, it, expect, beforeEach, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useStats, setSocketFactory } from './useStats'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor(public url: string) { FakeWebSocket.instances.push(this) }
  close() { this.onclose?.() }
}

let mock: MockAdapter
beforeEach(() => {
  mock = new MockAdapter(http)
  FakeWebSocket.instances = []
  localStorage.clear()
})

describe('useStats', () => {
  it('先拉 REST 初值，再接收 WS 帧', async () => {
    mock.onGet('/api/stats').reply(200,
      { total: 3, joined: 1, notJoined: 2, teamCount: 1, pendingTeamCount: 1 })
    setSocketFactory(url => new FakeWebSocket(url) as unknown as WebSocket)
    const { stats, start, stop } = useStats()
    start()
    await vi.waitFor(() => expect(stats.value?.total).toBe(3))
    const ws = FakeWebSocket.instances[0]!
    expect(ws.url).toContain('/ws/stats?token=')
    ws.onmessage?.({ data: JSON.stringify(
      { total: 5, joined: 4, notJoined: 1, teamCount: 2, pendingTeamCount: 0 }) })
    await vi.waitFor(() => expect(stats.value?.total).toBe(5))
    stop()
  })

  it('REST 初值失败不阻塞 WS 更新', async () => {
    mock.onGet('/api/stats').reply(500, { code: 'INTERNAL', message: 'x' })
    setSocketFactory(url => new FakeWebSocket(url) as unknown as WebSocket)
    const { stats, start, stop } = useStats()
    start()
    const ws = FakeWebSocket.instances[0]!
    ws.onmessage?.({ data: JSON.stringify(
      { total: 7, joined: 7, notJoined: 0, teamCount: 3, pendingTeamCount: 0 }) })
    await vi.waitFor(() => expect(stats.value?.total).toBe(7))
    stop()
  })
})
