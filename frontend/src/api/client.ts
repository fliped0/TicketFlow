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
  PURCHASE_LIMIT: '你在同一场次已有有效订单，请在我的订单中查看。',
  SOLD_OUT: '这个票档已售罄，请刷新库存并选择其他票档。',
  SALE_NOT_STARTED: '场次尚未开售，请在开售后再试。',
  SALE_ENDED: '场次已经停售。',
  NOT_ON_SALE: '活动已下架，暂时无法购买。',
  ASYNC_REQUIRED: '场次已改为异步抢票，请刷新场次后重新确认。',
  SYNC_REQUIRED: '场次使用普通购票，请刷新场次后重新确认。',
  ASYNC_PAUSED: '抢票服务暂时暂停，请保留原请求并稍后恢复。',
  ASYNC_DISABLED: '抢票服务尚未启用，请稍后恢复原请求。',
  DEPENDENCY_UNAVAILABLE: '抢票服务暂时不可用，请稍后恢复原请求。',
  ADMISSION_EXPIRED: '本次受理超时，未生成订单，可重新确认抢票。',
  PROCESSING_DEADLINE_EXCEEDED: '本次抢票处理超时，未生成订单。',
  ACCOUNT_DISABLED: '账号不可用，请联系管理员。',
  ORDER_EXPIRED: '订单已超过支付截止时间，请刷新订单状态。',
  ORDER_STATE_CONFLICT: '订单当前状态不允许此操作，请刷新后查看。',
  PAYMENT_SIMULATED_FAILURE: '本次模拟支付失败，你可以在支付期限内重新确认支付。',
  REFUND_CLOSED: '场次已经开场，无法申请模拟退款。',
  REFUND_SIMULATED_FAILURE: '本次模拟退款失败，订单仍为已支付，可在开场前重新确认退款。',
  IDEMPOTENCY_CONFLICT: '原操作记录与服务端不一致，请保留记录并核查订单。',
}
export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 0, public traceId = '', public retryAfter = 0, public data?: unknown) {
    super(message); this.name = 'ApiError'
  }
}
export function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作未完成，请稍后重试。'
}
interface Options {
  method?: 'GET' | 'POST'; body?: unknown; authenticated?: boolean; signal?: AbortSignal; idempotencyKey?: string
}
export interface ApiReply<T> { data: T; replayed: boolean; traceId: string; serverTime: number; retryAfter: number }
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  return (await apiReply<T>(path, options)).data
}
export async function apiReply<T>(path: string, options: Options = {}): Promise<ApiReply<T>> {
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
        ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: controller.signal, credentials: 'omit', redirect: 'error', cache: 'no-store',
    })
    if (response.status === 401 && token && accessToken.value === token) clearToken(messages.UNAUTHENTICATED)
    let payload: { code?: string; data?: T; traceId?: string; replayed?: boolean }
    try { payload = await response.json() } catch {
      throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。', response.status)
    }
    if (!payload || typeof payload.code !== 'string') throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。', response.status)
    if (!response.ok || payload.code !== 'OK') {
      throw new ApiError(payload.code, messages[payload.code] ?? '服务暂时无法完成请求，请稍后重试。', response.status,
        typeof payload.traceId === 'string' ? payload.traceId : '', Math.min(3600, Math.max(0, Number(response.headers.get('Retry-After')) || 0)), payload.data)
    }
    if (payload.data === undefined || payload.data === null) throw new ApiError('INVALID_RESPONSE', '服务返回异常，请稍后重试。')
    if (options.idempotencyKey && typeof payload.replayed !== 'boolean') throw new ApiError('INVALID_RESPONSE', '交易回执不完整，请保留原操作并恢复结果。')
    const serverTime = Date.parse(response.headers.get('Date') ?? '')
    return { data: payload.data, replayed: payload.replayed === true, traceId: payload.traceId ?? '', serverTime: Number.isFinite(serverTime) ? serverTime : Date.now(), retryAfter: Math.min(3600, Math.max(0, Number(response.headers.get('Retry-After')) || 0)) }
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (options.signal?.aborted) throw error
    throw new ApiError('NETWORK_ERROR', controller.signal.aborted ? '请求超时，请重试。' : '无法连接服务，请确认后端已启动。')
  } finally {
    clearTimeout(timeout); options.signal?.removeEventListener('abort', abort)
  }
}
