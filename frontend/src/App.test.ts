import { afterEach, expect, test, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { createAppRouter } from './router'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
})

test('toolchain smoke: Vue SFC, router, Pinia and Element Plus render together', async () => {
  // jsdom has no layout/scroll implementation; real scrolling belongs to browser tests.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  const router = createAppRouter(createMemoryHistory())
  await router.push('/')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  expect(wrapper.get('h1').text()).toBe('让每次出发，都有期待。')
  expect(wrapper.get('.el-tag').text()).toBe('前端环境已就绪')
})
