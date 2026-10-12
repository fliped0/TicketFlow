import { createRouter, type RouterHistory } from 'vue-router'

export function createAppRouter(history: RouterHistory) {
  return createRouter({
    history,
    routes: [
      { path: '/', component: () => import('./views/HomeView.vue') },
      { path: '/events/:id', component: () => import('./views/EventView.vue') },
      { path: '/login', component: () => import('./views/AuthView.vue') },
      { path: '/register', component: () => import('./views/AuthView.vue') },
      { path: '/:pathMatch(.*)*', component: () => import('./views/NotFoundView.vue') },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
}
