// Owns only its Java/preview/test children. Credentials stay in process memory.
import { spawn } from 'node:child_process'
import { randomBytes, generateKeyPairSync } from 'node:crypto'
import { readFile, writeFile, mkdir, unlink } from 'node:fs/promises'
import { createWriteStream } from 'node:fs'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const root = path.resolve(frontend, '..')
if (process.env.TF_DB_NAME !== 'ticketflow_test' || process.env.TF_DB_USER !== 'tf_test') throw new Error('Refusing live tests without dedicated test database/account')
const runId = randomBytes(6).toString('hex')
const directory = path.join(root, '.tools', 'frontend-live', runId)
await mkdir(directory, { recursive: true })
const report = { runId, database: 'ticketflow_test', passed: false, startedAt: new Date().toISOString(), cleanup: false }
const children = []
const admin = { username: 'web_admin_' + runId, password: randomBytes(24).toString('base64url') }
let base, adminToken, fixture
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
function start(command, args, env, logName) {
  const child = spawn(command, args, { cwd: frontend, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const log = createWriteStream(path.join(directory, logName))
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false })
  child.logDone = new Promise(resolve => log.once('finish', resolve))
  child.once('close', () => log.end())
  children.push(child)
  child.on('error', () => {})
  return child
}
async function exited(child) {
  if (child.exitCode !== null) return child.exitCode
  return new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject) })
}
async function call(method, route, body, token = adminToken) {
  const result = await fetch(base + '/api/v1' + route, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(10_000),
  })
  if (!result.ok) throw new Error(`Fixture API ${method} ${route} HTTP ${result.status}`)
  return (await result.json()).data
}
async function availablePort() {
  const server = createServer()
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  const port = server.address().port
  await new Promise(resolve => server.close(resolve))
  return port
}
async function ready(url, child) {
  const end = Date.now() + 45_000
  while (Date.now() < end) {
    if (child.exitCode !== null) throw new Error('Owned server exited before readiness')
    try { if ((await fetch(url, { signal: AbortSignal.timeout(1000) })).ok) return } catch {}
    await sleep(200)
  }
  throw new Error('Owned server readiness timeout')
}
try {
  const keys = generateKeyPairSync('rsa', { modulusLength: 2048, privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } })
  await writeFile(path.join(directory, 'private.pem'), keys.privateKey)
  await writeFile(path.join(directory, 'public.pem'), keys.publicKey)
  const env = { ...process.env, TF_ADMIN_USERNAME: admin.username, TF_ADMIN_PASSWORD: admin.password,
    TF_JWT_PRIVATE_KEY: path.join(directory, 'private.pem'), TF_JWT_PUBLIC_KEY: path.join(directory, 'public.pem'),
    TF_REDIS_ENABLED: 'false', TF_ASYNC_ENABLED: 'false', TF_ASYNC_JOBS_ENABLED: 'false' }
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe') : 'java'
  const backend = start(java, ['-jar', path.join(root, 'backend/target/ticketflow-0.1.0-SNAPSHOT.jar'), '--server.address=127.0.0.1', '--server.port=0', '--ticketflow.expiry.enabled=false'], env, 'java.log')
  for (let attempts = 0; attempts < 225; attempts++) {
    const log = await readFile(path.join(directory, 'java.log'), 'utf8').catch(() => '')
    const match = log.match(/Tomcat started on port (\d+)/)
    if (match) { base = 'http://127.0.0.1:' + match[1]; break }
    if (backend.exitCode !== null) throw new Error('Test backend exited; inspect ignored run log')
    await sleep(200)
  }
  if (!base) throw new Error('Test backend port discovery timeout')
  await ready(base + '/actuator/health', backend)
  adminToken = (await call('POST', '/auth/login', admin, '')).accessToken
  const directoryArg = '.tools/frontend-live/' + runId + '/demo'
  for (let round = 0; round < 2; round++) {
    // Repeated seed is checked for exact identity reuse, not merely successful responses.
    const seed = start('python', [path.join(root, 'scripts/frontend-demo.py'), '--base', base, '--directory', directoryArg], { ...process.env, PYTHONUTF8: '1', PYTHONIOENCODING: 'utf-8' }, `seed-${round}.log`)
    seed.stdin.end(JSON.stringify(admin))
    if (await exited(seed) !== 0) throw new Error('Test catalogue preparation failed; inspect ignored seed log')
    await seed.logDone
    const result = JSON.parse(await readFile(path.join(directory, `seed-${round}.log`), 'utf8'))
    if (fixture && JSON.stringify(fixture) !== JSON.stringify(result)) throw new Error('Seed repeat created different resources')
    fixture = result
  }
  report.seedReuse = true
  const previewPort = await availablePort()
  const previewEnv = { ...process.env, TF_FRONTEND_API_TARGET: base }
  for (const key of Object.keys(previewEnv)) if (/^TF_(DB_|JWT_|ADMIN_)/.test(key)) delete previewEnv[key]
  const preview = start(process.execPath, [path.join(frontend, 'node_modules/vite/bin/vite.js'), 'preview', '--port', String(previewPort)], previewEnv, 'preview.log')
  const url = 'http://127.0.0.1:' + previewPort
  await ready(url, preview)
  const output = path.join(directory, 'browser-results.json')
  const tests = start(process.execPath, [path.join(frontend, 'node_modules/@playwright/test/cli.js'), 'test', '--config=playwright.live.config.ts'], {
    ...previewEnv, TF_FRONTEND_LIVE_URL: url, TF_FRONTEND_LIVE_FIXTURE: JSON.stringify(fixture), PLAYWRIGHT_JSON_OUTPUT_NAME: output,
  }, 'browser.log')
  const code = await exited(tests)
  await tests.logDone
  const results = JSON.parse(await readFile(output, 'utf8'))
  report.browser = results.stats
  report.passed = code === 0 && results.stats.unexpected === 0 && results.stats.skipped === 0
  if (!report.passed) throw new Error('Real browser tests failed; inspect ignored browser report')
} catch (error) {
  report.error = error.message
  process.exitCode = 1
} finally {
  if (fixture && adminToken) {
    try {
      for (const event of fixture.events) {
        const actual = await call('GET', '/admin/events/' + event.id)
        await call('PUT', '/admin/events/' + event.id + '/status', { status: 'OFF_SALE', expectedVersion: actual.version })
      }
      report.cleanup = true
    } catch { report.cleanup = false; process.exitCode = 1 }
  }
  for (const child of children.reverse()) {
    if (child.exitCode === null) {
      const stopped = exited(child).catch(() => {})
      child.kill('SIGTERM')
      await Promise.race([stopped, sleep(3000)])
      if (child.exitCode === null) child.kill('SIGKILL')
    }
  }
  await unlink(path.join(directory, 'private.pem')).catch(() => {})
  await unlink(path.join(directory, 'public.pem')).catch(() => {})
  report.finishedAt = new Date().toISOString()
  await writeFile(path.join(directory, 'summary.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
}
