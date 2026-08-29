import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from './api/http'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    {
      path: '/admin',
      component: () => import('./views/AdminLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/admin/home' },
        { path: 'home', component: () => import('./views/HomeView.vue') },
        { path: 'activity', component: () => import('./views/ActivityView.vue') },
      ],
    },
    { path: '/form/:token', component: () => import('./views/FormView.vue') },
    { path: '/', redirect: '/admin/home' },
  ],
})

router.beforeEach(to => {
  if (to.meta.requiresAuth && !getToken()) return '/login'
  return true
})
