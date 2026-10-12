import { createRouter, type RouterHistory } from 'vue-router'

export function createAppRouter(history: RouterHistory) {
  return createRouter({
    history,
    routes: [
      { path: '/', component: () => import('./views/HomeView.vue') },
      { path: '/:pathMatch(.*)*', component: () => import('./views/NotFoundView.vue') },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
}
