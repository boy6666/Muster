import { reactive } from 'vue'

export type ToastType = 'success' | 'error' | 'warning'

export interface ToastItem {
  id: number
  type: ToastType
  msg: string
}

/** 模块级共享数组,ToastHost 负责渲染 */
export const toasts = reactive<ToastItem[]>([])

let nextId = 1
const DURATION = 3000

function push(type: ToastType, msg: string) {
  const id = nextId++
  toasts.push({ id, type, msg })
  setTimeout(() => {
    const i = toasts.findIndex(t => t.id === id)
    if (i !== -1) toasts.splice(i, 1)
  }, DURATION)
}

export const toast = {
  success: (msg: string) => push('success', msg),
  error: (msg: string) => push('error', msg),
  warning: (msg: string) => push('warning', msg),
}
