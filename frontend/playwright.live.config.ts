import { defineConfig, devices } from '@playwright/test'

if (!process.env.TF_FRONTEND_LIVE_URL || !process.env.TF_FRONTEND_LIVE_FIXTURE) {
  throw new Error('Run live browser tests with scripts/test-frontend.ps1 -Live')
}
export default defineConfig({
  testDir: './tests/live', fullyParallel: true, forbidOnly: true, retries: 0,
  reporter: [['list'], ['json']],
  use: { baseURL: process.env.TF_FRONTEND_LIVE_URL, trace: 'off', screenshot: 'only-on-failure' },
  projects: [
    { name: 'live-desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'live-mobile', use: { ...devices['Pixel 7'] } },
  ],
})
