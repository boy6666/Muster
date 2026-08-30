import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles/tokens.css'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/http'

setUnauthorizedHandler(() => {
  if (!location.pathname.startsWith('/form/')) {
    router.push('/login').catch(() => {})
  }
})

createApp(App).use(createPinia()).use(router).mount('#app')
