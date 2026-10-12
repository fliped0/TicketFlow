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
  vi.unstubAllGlobals()
})

test('catalogue treats untrusted event names as text and preserves filters in the URL', async () => {
  // jsdom has no layout/scroll implementation; real scrolling belongs to browser tests.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'OK', data: { items: [{ id: '1', name: '<img src=x onerror=alert(1)>', category: '音乐', city: '上海', venue: '音乐厅' }], page: 1, size: 9, total: 1 } }))))
  vi.stubGlobal('fetch', fetcher)
  const router = createAppRouter(createMemoryHistory())
  await router.push('/')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  expect(wrapper.get('h1').text()).toBe('让每次出发，都有期待。')
  expect(wrapper.get('.event-copy h3').text()).toBe('<img src=x onerror=alert(1)>')
  expect(wrapper.find('img').exists()).toBe(false)
  await wrapper.get('#city').setValue('上海')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.query.city).toBe('上海')
  expect(fetcher.mock.calls.at(-1)![0]).toContain('city=%E4%B8%8A%E6%B5%B7')
})
