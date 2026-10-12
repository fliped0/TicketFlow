import { ApiError } from '../api/client'
import type { TradeResult } from '../api/types'

export interface TradePreview { eventName: string; tierName: string; startsAt: string; amountFen: number }
export interface TradeCommand { operation: 'CREATE' | 'PAY'; targetId: string; preview: TradePreview }
export interface TradeRecord extends TradeCommand {
  schemaVersion: 1; owner: string; origin: string; key: string; createdAt: number; retryAt: number;
  state: 'UNKNOWN' | 'SUCCEEDED' | 'REJECTED'; result: TradeResult | null; errorCode: string;
}
export function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
export function validReceipt(value: unknown, command: TradeCommand): value is TradeResult {
  if (!value || typeof value !== 'object') return false
  const result = value as TradeResult
  const states = ['PENDING', 'PAID', 'CANCELLED', 'CLOSED', 'REFUNDED']
  return validId(result.orderId) && result.amountFen === command.preview.amountFen && states.includes(result.currentOrderStatus)
    && typeof result.expiresAt === 'string' && Number.isFinite(Date.parse(result.expiresAt)) && result.refundId === null
    && (command.operation === 'CREATE'
      ? result.operationStatus === 'PENDING' && result.paymentId === null
      : result.orderId === command.targetId && result.operationStatus === 'PAID' && validId(result.paymentId) && ['PAID', 'REFUNDED'].includes(result.currentOrderStatus))
}
export function validRecord(value: unknown, owner: string): value is TradeRecord {
  if (!value || typeof value !== 'object') return false
  const r = value as TradeRecord
  const p = r.preview
  return r.schemaVersion === 1 && r.owner === owner && validId(owner) && r.origin === location.origin
    && ['CREATE', 'PAY'].includes(r.operation) && validId(r.targetId) && typeof r.key === 'string' && /^[A-Za-z0-9_-]{16,64}$/.test(r.key)
    && Number.isSafeInteger(r.createdAt) && r.createdAt > 0 && Number.isSafeInteger(r.retryAt) && r.retryAt >= 0
    && !!p && typeof p.eventName === 'string' && p.eventName.length <= 100 && typeof p.tierName === 'string' && p.tierName.length <= 100
    && typeof p.startsAt === 'string' && Number.isFinite(Date.parse(p.startsAt)) && Number.isSafeInteger(p.amountFen) && p.amountFen > 0 && p.amountFen <= 100_000_000
    && typeof r.errorCode === 'string' && r.errorCode.length <= 64
    && (r.state === 'UNKNOWN' || r.state === 'REJECTED' ? r.result === null : r.state === 'SUCCEEDED' && validReceipt(r.result, r))
}
export function storageKey(owner: string) { return `ticketflow.trade.v1.${owner}` }
function unavailable() { return new ApiError('TRADE_STORAGE_ERROR', '交易记录无法安全读取或保存。请保留浏览器数据并核查订单，暂时不能发起新交易。') }
export function readRecord(owner: string): TradeRecord | null {
  try {
    const raw = localStorage.getItem(storageKey(owner))
    if (raw === null) return null
    const record: unknown = JSON.parse(raw)
    if (!validRecord(record, owner)) throw unavailable()
    return record
  } catch { throw unavailable() }
}
export function writeRecord(record: TradeRecord) {
  if (!validRecord(record, record.owner)) throw unavailable()
  try {
    const raw = JSON.stringify(record)
    localStorage.setItem(storageKey(record.owner), raw)
    if (localStorage.getItem(storageKey(record.owner)) !== raw) throw unavailable()
  } catch { throw unavailable() }
}
export function dismissRecord(owner: string, key: string) {
  const current = readRecord(owner)
  if (!current || current.key !== key || current.state === 'UNKNOWN') throw unavailable()
  try { localStorage.removeItem(storageKey(owner)) } catch { throw unavailable() }
}
export const definiteRejections: Record<string, number> = {
  PURCHASE_LIMIT: 409, SOLD_OUT: 409, SALE_NOT_STARTED: 409, SALE_ENDED: 409, NOT_ON_SALE: 409,
  ASYNC_REQUIRED: 409, ORDER_EXPIRED: 409, ORDER_STATE_CONFLICT: 409, NOT_FOUND: 404, PAYMENT_SIMULATED_FAILURE: 422,
}
