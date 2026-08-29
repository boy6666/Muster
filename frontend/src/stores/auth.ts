import { defineStore } from 'pinia'
import { http, setToken, getToken } from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: getToken() as string | null }),
  actions: {
    async login(username: string, password: string) {
      const { data } = await http.post('/api/auth/login', { username, password })
      this.token = data.token
      setToken(data.token)
    },
    logout() {
      this.token = null
      setToken(null)
    },
    async changePassword(oldPassword: string, newPassword: string) {
      await http.put('/api/auth/password', { oldPassword, newPassword })
    },
  },
})
