import { createApp, h, reactive } from 'vue'
import UiModal from './UiModal.vue'

type ConfirmType = 'danger' | 'warning'

const state = reactive({
  visible: false,
  message: '',
  title: '确认操作',
  type: 'warning' as ConfirmType,
})

let resolveFn: (() => void) | null = null
let rejectFn: (() => void) | null = null
let hostMounted = false

function settle(ok: boolean) {
  state.visible = false
  const fn = ok ? resolveFn : rejectFn
  resolveFn = rejectFn = null
  fn?.()
}

function ensureHost() {
  if (hostMounted) return
  hostMounted = true
  const host = document.createElement('div')
  document.body.appendChild(host)
  createApp({
    setup() {
      return () =>
        h(
          UiModal,
          {
            visible: state.visible,
            'onUpdate:visible': (v: boolean) => {
              if (!v) settle(false)
              else state.visible = true
            },
            title: state.title,
            modalClass: 'modal-confirm',
          },
          {
            default: () => h('div', { class: 'confirm-msg' }, state.message),
            footer: () => [
              h('button', { class: 'btn ghost', onClick: () => settle(false) }, '取消'),
              h(
                'button',
                { class: state.type === 'danger' ? 'btn danger' : 'btn primary', onClick: () => settle(true) },
                '确认',
              ),
            ],
          },
        )
    },
  }).mount(host)
}

/**
 * 共享确认弹窗。确认 → resolve;取消 / 遮罩 / × → reject(不带错误对象,调用方只 catch 不看值)。
 * 同一时刻仅支持一个弹窗,后一次调用覆盖前一次未决的 Promise。
 */
export function confirm(message: string, title = '确认操作', type: ConfirmType = 'warning'): Promise<void> {
  ensureHost()
  return new Promise((resolve, reject) => {
    resolveFn = resolve
    rejectFn = reject
    state.message = message
    state.title = title
    state.type = type
    state.visible = true
  })
}
