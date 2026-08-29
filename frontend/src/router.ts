import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/admin/home', component: () => import('./views/HomeView.vue') },
    { path: '/', redirect: '/admin/home' },
  ],
})
