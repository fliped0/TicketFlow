import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError } from '../api/client'
import type { Token, User } from '../api/types'
import { accessToken, clearToken, saveToken } from './session'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null); const busy = ref(false)
  const authenticated = computed(() => !!accessToken.value && !!user.value)
  let restoreRequest: { token: string; promise: Promise<void> } | null = null
  watch(accessToken, () => { user.value = null }, { flush: 'sync' })
  async function restore() {
    if (!accessToken.value || user.value) return
    const token = accessToken.value
    if (restoreRequest?.token === token) return restoreRequest.promise
    const request = { token, promise: Promise.resolve() }
    request.promise = (async () => {
      const current = await api<User>('/users/me', { authenticated: true })
      if (accessToken.value === token) user.value = current
    })().finally(() => { if (restoreRequest === request) restoreRequest = null })
    restoreRequest = request
    return request.promise
  }
  async function login(username: string, password: string) {
    if (busy.value) return
    busy.value = true
    try {
      const result = await api<Token>('/auth/login', { method: 'POST', body: { username, password } })
      if (typeof result.accessToken !== 'string' || result.tokenType !== 'Bearer') throw new ApiError('INVALID_RESPONSE', '登录返回异常，请重试。')
      try { saveToken(result.accessToken) } catch { throw new ApiError('STORAGE_UNAVAILABLE', '浏览器无法保存登录状态，请允许本机页面使用存储后重试。') }
      await restore()
    } finally { busy.value = false }
  }
  async function register(username: string, password: string) {
    if (busy.value) return
    busy.value = true
    try { await api<User>('/auth/register', { method: 'POST', body: { username, password } }) }
    finally { busy.value = false }
  }
  function logout() { clearToken(); user.value = null }
  return { user, busy, authenticated, restore, login, register, logout }
})
