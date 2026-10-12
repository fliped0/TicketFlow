import { createRouter, type RouterHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

export function createAppRouter(history: RouterHistory) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', component: () => import('./views/HomeView.vue') },
      { path: '/events/:id', component: () => import('./views/EventView.vue') },
      { path: '/login', component: () => import('./views/AuthView.vue') },
      { path: '/register', component: () => import('./views/AuthView.vue') },
      { path: '/orders', component: () => import('./views/OrdersView.vue'), meta: { requiresAuth: true } },
      { path: '/orders/:id', component: () => import('./views/OrderView.vue'), meta: { requiresAuth: true } },
      { path: '/:pathMatch(.*)*', component: () => import('./views/NotFoundView.vue') },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.beforeEach(async to => {
    if (!to.meta.requiresAuth) return true
    const auth = useAuthStore()
    try { await auth.restore() } catch { /* Require a validated identity, keep unresolved records intact. */ }
    return auth.authenticated ? true : { path: '/login', query: { redirect: to.fullPath } }
  })
  return router
}
