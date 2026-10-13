import { ApiError } from './client'
import { accessToken, clearToken } from '../stores/session'
import type { Order, TradeResult } from './types'
import { validId, validReceipt } from '../trade/journal'

export interface Confirmation {
  confirmationId: string; sessionId: string; orderId: string; operation: 'cancel' | 'refund';
  state: 'PENDING' | 'EXECUTING' | 'UNKNOWN' | 'SUCCEEDED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
  expiresAt: string; amountFen: number; snapshot: Order; simulation: true;
  result: { code: string; data?: TradeResult } | null;
}
export interface QueryCard { tool: string; data: Record<string, unknown>; source: { method: string; path: string; queriedAt?: string } }
export interface Citation { citationId: string; ruleId: string; version: string; path: string; section: string; excerpt: string; line: number }
export interface ChatReply { sessionId: string; modelMode: string; message: string; cards: QueryCard[]; citations?: Citation[]; confirmations?: Confirmation[] }
export const validConfirmationId = (id: unknown): id is string => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)
export const terminalConfirmation = (state: string) => ['SUCCEEDED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(state)
export function validConfirmation(value: unknown): value is Confirmation {
  if (!value || typeof value !== 'object') return false
  const c = value as Confirmation; const s = c.snapshot
  if (!validConfirmationId(c.confirmationId) || !validId(c.orderId) || !['cancel', 'refund'].includes(c.operation)
    || !['PENDING', 'EXECUTING', 'UNKNOWN', 'SUCCEEDED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(c.state)
    || c.simulation !== true || !Number.isFinite(Date.parse(c.expiresAt)) || !s || s.orderId !== c.orderId
    || !Number.isSafeInteger(s.amountFen) || s.amountFen <= 0 || !s.snapshot
    || typeof s.snapshot.eventName !== 'string' || typeof s.snapshot.tierName !== 'string'
    || !Number.isFinite(Date.parse(s.snapshot.startsAt)) || c.amountFen !== (c.operation === 'cancel' ? 0 : s.amountFen)) return false
  if (c.state === 'SUCCEEDED') return c.result?.code === 'OK' && validReceipt(c.result.data, {
    operation: c.operation === 'cancel' ? 'CANCEL' : 'REFUND', targetId: c.orderId,
    preview: { eventName: s.snapshot.eventName, tierName: s.snapshot.tierName, startsAt: s.snapshot.startsAt, amountFen: s.amountFen },
  })
  return c.result === null || (typeof c.result === 'object' && typeof c.result.code === 'string')
}
const messages: Record<string, string> = {
  SESSION_NOT_FOUND: '会话已过期，请新建会话后重新提问。', SESSION_BUSY: '该会话正在处理，请稍候。',
  MODEL_NOT_CONFIGURED: '自然语言助手尚未启用，可使用下方的直接查询。', DEMO_COMMAND_REQUIRED: '当前为演示模式，请使用页面给出的固定指令。',
  MODEL_INVALID_OUTPUT: '助手未能解析本次请求，请写明活动或订单编号后再试。', MODEL_UNAVAILABLE: '模型服务暂不可用，请稍后再试。',
  RULES_UNAVAILABLE: '审核规则资料暂不可用，请稍后再试。', JAVA_UNAVAILABLE: '业务服务连接失败，请稍后再试。',
  CONFIRMATION_NOT_FOUND: '确认记录不存在或无权访问，请保留本机记录并核查订单。',
  CONFIRMATION_BUSY: '操作仍在处理中，请稍后查询结果。', CONFIRMATION_UNRESOLVED: '该订单已有待确认或未决操作，请先处理原记录。',
  CONFIRMATION_ORDER_STATE: '订单当前状态不允许此操作。', CONFIRMATION_REFUND_CLOSED: '场次已开场，不能模拟退款。',
  CONFIRMATION_NOT_STARTED: '尚未确认执行，请先查询确认状态。', CONFIRMATION_NOT_PENDING: '此确认已开始执行或已结束，请查询结果。',
  CONFIRMATIONS_UNAVAILABLE: '确认记录暂不可用，请保留记录并核查订单。',
}
export async function agentApi<T>(path: string, method: 'GET' | 'POST' | 'DELETE' = 'GET', body?: unknown): Promise<T> {
  if (!/^\/(chat|query|sessions\/[0-9a-f-]{36}|confirmations(?:\/[0-9a-f-]{36}(?:\/(execute|recover))?)?)$/.test(path)) throw new Error('Invalid agent path')
  const token = accessToken.value
  if (!token) throw new ApiError('UNAUTHENTICATED', '请先登录。', 401)
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 50_000)
  try {
    const response = await fetch('/agent/v1' + path, { method, body: body === undefined ? undefined : JSON.stringify(body),
      headers: { Accept: 'application/json', Authorization: 'Bearer ' + token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal })
    if (response.status === 401 && accessToken.value === token) clearToken('登录已失效，请重新登录后查询原确认记录。')
    const payload = await response.json()
    if (!response.ok || payload?.code !== 'OK') {
      const code = typeof payload?.code === 'string' ? payload.code : 'INVALID_RESPONSE'
      throw new ApiError(code, response.status === 401 ? '请重新登录。' : response.status === 429 ? '请求繁忙或额度已达上限，请稍后再试。' : messages[code] ?? '助手暂时无法完成请求，请稍后再试。', response.status)
    }
    if (!payload.data || typeof payload.data !== 'object') throw new ApiError('INVALID_RESPONSE', '助手返回内容无法核对。')
    return payload.data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('AGENT_NETWORK_ERROR', '无法取得助手响应，请确认助手服务已启动；已确认操作请先查询原记录。')
  } finally { clearTimeout(timeout) }
}
