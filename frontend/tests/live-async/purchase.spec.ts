import { randomUUID } from 'node:crypto'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { expect, test as base, type APIRequestContext, type Page } from '@playwright/test'
import type { Order } from '../../src/api/types'
const runId = (JSON.parse(process.env.TF_FRONTEND_LIVE_FIXTURE!) as { runId: string }).runId
const adminHeaders = { Authorization: 'Bearer ' + process.env.TF_FRONTEND_LIVE_ADMIN_TOKEN! }
const test = base.extend<{ accounts: string[] }>({ accounts: async ({ request }, use) => {
  const tokens: string[] = []; await use(tokens)
  for (const token of tokens) {
    const response = await request.get('/api/v1/orders?size=100', { headers: { Authorization: 'Bearer ' + token } }); expect(response.status()).toBe(200)
    for (const order of (await response.json()).data.items as Order[]) {
      if (!['PENDING', 'PAID'].includes(order.status)) continue
      const cleanup = await request.post(`/api/v1/orders/${order.orderId}/${order.status === 'PAID' ? 'refunds' : 'cancel'}`, { headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': randomUUID() }, data: {} })
      expect(cleanup.status()).toBe(200)
    }
  }
} })
test.setTimeout(90000)
async function user(page: Page, accounts: string[]) {
  const credentials = { username: 'async_' + randomUUID().replaceAll('-', '').slice(0, 20), password: 'Test_' + randomUUID() }
  await page.goto('/register'); await page.getByLabel('账号', { exact: true }).fill(credentials.username); await page.getByLabel('密码', { exact: true }).fill(credentials.password); await page.getByLabel('确认密码').fill(credentials.password)
  await page.getByRole('button', { name: '注册账号' }).click(); await expect(page.getByText('注册成功，请使用刚才的账号和密码登录。')).toBeVisible()
  await page.getByLabel('密码', { exact: true }).fill(credentials.password); await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.locator('.account-name')).toHaveText(credentials.username)
  const token = (await page.evaluate(() => sessionStorage.getItem('ticketflow.auth.v1')))!
  accounts.push(token); return { ...credentials, token }
}
async function fixture(request: APIRequestContext, capacity: number) {
  const eventResponse = await request.post('/api/v1/admin/events', { headers: adminHeaders, data: { name: '异步抢票 ' + runId + ' ' + randomUUID().slice(0, 8), description: '真实异步浏览器验收', category: '音乐', city: '上海', venue: '演示音乐厅' } })
  expect(eventResponse.status()).toBe(201); const event = (await eventResponse.json()).data
  const start = Date.now() + 12000
  const sessionResponse = await request.post('/api/v1/admin/events/' + event.id + '/sessions', { headers: adminHeaders, data: { startsAt: new Date(start + 86400000).toISOString(), saleStartAt: new Date(start).toISOString(), saleEndAt: new Date(start + 3600000).toISOString() } })
  expect(sessionResponse.status()).toBe(201); const session = (await sessionResponse.json()).data
  const tierResponse = await request.post('/api/v1/admin/sessions/' + session.id + '/tiers', { headers: adminHeaders, data: { name: '标准票', priceFen: 9900, capacity } })
  expect(tierResponse.status()).toBe(201); const tier = (await tierResponse.json()).data
  const mode = await request.put('/api/v1/admin/sessions/' + session.id + '/purchase-mode', { headers: adminHeaders, data: { mode: 'ASYNC', expectedVersion: session.version } })
  expect(mode.status()).toBe(200); expect((await mode.json()).data.phase).toBe('READY')
  const published = await request.put('/api/v1/admin/events/' + event.id + '/status', { headers: adminHeaders, data: { status: 'ON_SALE', expectedVersion: event.version } }); expect(published.status()).toBe(200)
  await expect.poll(async () => (await (await request.get('/api/v1/sessions/' + session.id + '/tiers')).json()).data.items[0].saleStatus, { timeout: 20000, intervals: [1000] }).toBe('ON_SALE')
  return { event, session, tier }
}
async function prepare(page: Page, eventId: string) {
  await page.goto('/events/' + eventId); await page.getByRole('button', { name: /标准票/ }).click(); await page.getByRole('button', { name: '确认选票并抢票' }).click()
  const dialog = page.getByRole('dialog', { name: '确认抢票' }); await dialog.getByRole('checkbox').check(); return dialog
}
async function screenshot(page: Page, name: string, project: string) {
  const folder = path.resolve('../docs/assets/04/20261014-frontend-async'); await mkdir(folder, { recursive: true })
  await page.screenshot({ path: path.join(folder, name + '-' + (project.endsWith('mobile') ? 'mobile' : 'desktop') + '.png'), fullPage: true, animations: 'disabled' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
}
async function orders(request: APIRequestContext, token: string): Promise<Order[]> {
  const response = await request.get('/api/v1/orders?size=100', { headers: { Authorization: 'Bearer ' + token } }); expect(response.status()).toBe(200); return (await response.json()).data.items
}

test('real async admission, refresh and payment preserve one request and one order', async ({ page, request, accounts }, info) => {
  const account = await user(page, accounts); const f = await fixture(request, 2)
  const posts: string[] = []; let id = ''; let allowQuery = false
  await page.route('**/api/v1/purchase-requests', async route => {
    posts.push(route.request().headers()['idempotency-key']!)
    const response = await route.fetch(); expect(response.status()).toBe(202); const data = (await response.json()).data
    expect(data.state).toBe('ACCEPTED'); expect(data.orderId).toBeNull(); id = data.requestId
    await route.fulfill({ response })
  })
  await page.route('**/api/v1/purchase-requests/*', async route => {
    if (!allowQuery) return route.abort('connectionreset') // Admission is real; delay browser visibility of the result.
    await route.continue()
  })
  const dialog = await prepare(page, f.event.id); await dialog.getByRole('button', { name: '确认提交抢票' }).click()
  await expect(page.getByRole('region', { name: '抢票结果与恢复' })).toContainText('已受理')
  await screenshot(page, 'async-accepted', info.project.name)
  await page.reload(); allowQuery = true
  await expect(page).toHaveURL(/orders\/\d+$/, { timeout: 30000 }); expect(posts).toHaveLength(1)
  const list = await orders(request, account.token); expect(list).toHaveLength(1)
  const result = await request.get('/api/v1/purchase-requests/' + id, { headers: { Authorization: 'Bearer ' + account.token } })
  expect((await result.json()).data.orderId).toBe(list[0]!.orderId)
  await page.getByRole('button', { name: '模拟支付', exact: true }).click(); const payment = page.getByRole('dialog', { name: '确认模拟支付' }); await payment.getByRole('checkbox').check(); await payment.getByRole('button', { name: '确认模拟支付' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已支付'); await screenshot(page, 'async-paid', info.project.name)
})

test('real lost admission replays the original key and rejects another account query', async ({ page, request, accounts }, info) => {
  const first = await user(page, accounts); const f = await fixture(request, 2); const posts: string[] = []; let id = ''
  await page.route('**/api/v1/purchase-requests', async route => {
    posts.push(route.request().headers()['idempotency-key']!)
    const response = await route.fetch(); const payload = await response.json(); expect(response.ok()).toBe(true)
    if (posts.length === 1) { id = payload.data.requestId; return route.abort('connectionreset') }
    expect(payload.replayed).toBe(true); expect(payload.data.requestId).toBe(id); await route.fulfill({ response })
  })
  const dialog = await prepare(page, f.event.id); await dialog.getByRole('button', { name: '确认提交抢票' }).click(); await expect(dialog.getByRole('alert')).toBeVisible(); await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.reload(); await expect(page.getByRole('region', { name: '抢票结果与恢复' })).toContainText('抢票受理结果待确认')
  await screenshot(page, 'async-unknown', info.project.name); expect(posts).toHaveLength(1)
  await page.getByRole('button', { name: '退出登录' }).click(); const second = await user(page, accounts)
  await expect(page.getByRole('region', { name: '抢票结果与恢复' })).not.toBeVisible()
  const foreign = await request.get('/api/v1/purchase-requests/' + id, { headers: { Authorization: 'Bearer ' + second.token } }); expect(foreign.status()).toBe(404)
  await page.getByRole('button', { name: '退出登录' }).click(); await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(first.username); await page.getByLabel('密码', { exact: true }).fill(first.password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.account-name')).toHaveText(first.username); await page.getByRole('button', { name: '恢复原抢票请求' }).click()
  await expect(page.getByRole('region', { name: '抢票结果与恢复' }).getByRole('heading', { level: 2 })).toHaveText('抢票成功，订单已生成', { timeout: 30000 })
  expect(posts).toHaveLength(2); expect(posts[0]).toBe(posts[1]); expect(await orders(request, first.token)).toHaveLength(1)
})

test('real simultaneous last-ticket competition produces exactly one successful order', async ({ page, context, request, accounts }, info) => {
  const otherContext = await context.browser()!.newContext({ baseURL: process.env.TF_FRONTEND_LIVE_URL }); const other = await otherContext.newPage()
  try {
    const first = await user(page, accounts); const second = await user(other, accounts); const f = await fixture(request, 1)
    const firstDialog = await prepare(page, f.event.id); const secondDialog = await prepare(other, f.event.id)
    let arrivals = 0; let release!: () => void; const barrier = new Promise<void>(resolve => { release = resolve })
    for (const p of [page, other]) await p.route('**/api/v1/purchase-requests', async route => { if (++arrivals === 2) release(); await barrier; await route.continue() })
    await Promise.all([firstDialog.getByRole('button', { name: '确认提交抢票' }).click(), secondDialog.getByRole('button', { name: '确认提交抢票' }).click()])
    const a = page.getByRole('region', { name: '抢票结果与恢复' }); const b = other.getByRole('region', { name: '抢票结果与恢复' })
    await expect(a.getByRole('heading', { level: 2 })).toHaveText(/^(抢票成功，订单已生成|本次抢票未成功)$/, { timeout: 30000 }); await expect(b.getByRole('heading', { level: 2 })).toHaveText(/^(抢票成功，订单已生成|本次抢票未成功)$/, { timeout: 30000 })
    const headings = [await a.getByRole('heading', { level: 2 }).innerText(), await b.getByRole('heading', { level: 2 }).innerText()]
    expect(headings.filter(t => t === '抢票成功，订单已生成')).toHaveLength(1)
    const texts = [await a.innerText(), await b.innerText()]; expect(texts.filter(t => t.includes('票档已售罄'))).toHaveLength(1)
    const all = [...await orders(request, first.token), ...await orders(request, second.token)]; expect(all).toHaveLength(1); expect(all[0]!.status).toBe('PENDING'); expect(all[0]!.snapshot.tierId).toBe(f.tier.id)
    const stock = await request.get('/api/v1/sessions/' + f.session.id + '/tiers'); expect((await stock.json()).data.items[0].available).toBe(0)
    await screenshot(page, 'async-competition', info.project.name)
  } finally { await otherContext.close() }
})
