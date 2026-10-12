import { accessToken, clearToken } from '../stores/session'

const messages: Record<string, string> = {
  BAD_CREDENTIALS: '账号或密码不正确，请重试。',
  USERNAME_EXISTS: '这个账号已被注册，请换一个或直接登录。',
  UNAUTHENTICATED: '登录已失效，请重新登录。',
  FORBIDDEN: '当前账号没有操作权限。',
  NOT_FOUND: '内容不存在或已下架。',
  VALIDATION_ERROR: '请求参数不正确，请检查输入。',
  TEMPORARILY_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
  TOO_MANY_REQUESTS: '请求过于频繁，请稍后重试。',
}
export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 0, public traceId = '') {
    super(message); this.name = 'ApiError'
  }
}
export function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作未完成，请稍后重试。'
}
interface Options {
  method?: 'GET' | 'POST'; body?: unknown; authenticated?: boolean; signal?: AbortSignal
}
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid API path')
  const token = options.authenticated ? accessToken.value : ''
  if (options.authenticated && !token) throw new ApiError('UNAUTHENTICATED', messages.UNAUTHENTICATED!)
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  const timeout = setTimeout(abort, 10_000)
  try {
    const response = await fetch(`/api/v1${path}`, {
      method: options.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: controller.signal, credentials: 'omit', redirect: 'error', cache: 'no-store',
    })
    if (response.status === 401 && token && accessToken.value === token) clearToken(messages.UNAUTHENTICATED)
    let payload: { code?: string; data?: T; traceId?: string }
    try { payload = await response.json() } catch {
      throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。', response.status)
    }
    if (!payload || typeof payload.code !== 'string') throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。', response.status)
    if (!response.ok || payload.code !== 'OK') {
      throw new ApiError(payload.code, messages[payload.code] ?? '服务暂时无法完成请求，请稍后重试。', response.status,
        typeof payload.traceId === 'string' ? payload.traceId : '')
    }
    if (payload.data === undefined || payload.data === null) throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。')
    return payload.data
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (options.signal?.aborted) throw error
    throw new ApiError('NETWORK_ERROR', controller.signal.aborted ? '请求超时，请重试。' : '无法连接服务，请确认后端已启动。')
  } finally {
    clearTimeout(timeout); options.signal?.removeEventListener('abort', abort)
  }
}
