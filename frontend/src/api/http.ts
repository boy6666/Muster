import axios, { type AxiosError } from 'axios'

export interface ApiError {
  code: string
  message: string
  data?: unknown
  status?: number
}

const TOKEN_KEY = 'muster.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 把 axios 错误剥成统一 ApiError；非信封响应降级为 NETWORK。 */
export function toApiError(err: unknown): ApiError {
  const ax = err as AxiosError<{ code?: string; message?: string; data?: unknown }>
  const resp = ax?.response
  if (resp?.data && typeof resp.data === 'object' && 'code' in resp.data) {
    return { code: resp.data.code!, message: resp.data.message ?? '请求失败',
             data: resp.data.data, status: resp.status }
  }
  return { code: 'NETWORK', message: '网络异常，请重试', status: resp?.status }
}

let unauthorizedHandler: (() => void) | null = null
/** 注册 401 时的跳转行为（main.ts 里注册 router.push('/login')）。 */
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn
}

export const http = axios.create({ timeout: 15000 })

http.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  resp => resp,
  err => {
    const apiError = toApiError(err)
    const url: string = err?.config?.url ?? ''
    if (apiError.status === 401 && !url.startsWith('/api/auth/login')) {
      setToken(null)
      unauthorizedHandler?.()
    }
    return Promise.reject(apiError)
  },
)
