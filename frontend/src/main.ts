import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import Vant from 'vant'
import 'vant/lib/index.css'
import './styles/tokens.css'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/http'

setUnauthorizedHandler(() => {
  if (!location.pathname.startsWith('/form/')) {
    router.push('/login').catch(() => {})
  }
})

createApp(App).use(createPinia()).use(router).use(ElementPlus).use(Vant).mount('#app')
