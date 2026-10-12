import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('production build loads, routes and renders without overflow or runtime errors', async ({ page }) => {
  const errors: string[] = []
  await mockApi(page)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/')
  await expect(page).toHaveTitle('TicketFlow · 票务体验')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('让每次出发，都有期待。')
  await expect(page.getByRole('link', { name: /城市回声/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

  await page.goto('/missing-page')
  await expect(page.getByRole('heading', { name: '页面不存在' })).toBeVisible()
  await page.getByRole('link', { name: '返回首页' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: /城市回声/ })).toBeVisible()
  expect(errors).toEqual([])
})
