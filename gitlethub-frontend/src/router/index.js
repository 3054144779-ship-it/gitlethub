import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  // 登录与注册共用一个视图，切换时沿用同一实例，右侧表单原地切换
  { path: '/login', name: 'login', component: () => import('../views/AuthView.vue') },
  { path: '/register', name: 'register', component: () => import('../views/AuthView.vue') },
  {
    path: '/',
    name: 'repos',
    component: () => import('../views/RepoListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { requiresAuth: true }
  },
  // 仓库：/:owner/:repo 形式（与 GitHub 一致）
  {
    path: '/:owner/:repo',
    name: 'repo',
    component: () => import('../views/RepoDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:owner/:repo/commit/:sha',
    name: 'commit',
    component: () => import('../views/CommitDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:owner/:repo/pulls',
    name: 'pulls',
    component: () => import('../views/PRListView.vue'),
    meta: { requiresAuth: true }
  },
  // 注意：/pulls/new 必须排在同段数的 /pulls/:id 之前，否则会被当成 id
  {
    path: '/:owner/:repo/pulls/new',
    name: 'pull-new',
    component: () => import('../views/PRCreateView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:owner/:repo/pulls/:id',
    name: 'pull',
    component: () => import('../views/PRDetailView.vue'),
    meta: { requiresAuth: true }
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', redirect: { name: 'repos' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (to) =>
    to.hash ? { el: to.hash, top: 72, behavior: 'smooth' } : { top: 0 }
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && auth.isLoggedIn) {
    return { name: 'repos' }
  }
  return true
})

export default router
