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
        { path: 'home', component: () => import('./views/HomeView.vue'), meta: { title: '实时统计' } },
        { path: 'activity', component: () => import('./views/ActivityView.vue'), meta: { title: '活动管理' } },
        { path: 'roster', component: () => import('./views/RosterView.vue'), meta: { title: '花名册' } },
        { path: 'teams', component: () => import('./views/TeamView.vue'), meta: { title: '组管理' } },
        { path: 'audit', component: () => import('./views/AuditView.vue'), meta: { title: '审计日志' } },
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
