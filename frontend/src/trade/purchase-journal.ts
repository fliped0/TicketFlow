import { ref } from 'vue'
import { ApiError } from '../api/client'
import { validId, type TradePreview } from './journal'
import type { OrderSnapshot, OrderStatus } from '../api/types'

export interface PurchaseResult {
  requestId: string; state: 'ACCEPTED' | 'PROCESSING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'REJECTED';
  acceptedAt: string | null; createDeadline: string | null; orderId: string | null;
  failureCode: string | null; currentOrderStatus: OrderStatus | null; snapshot: OrderSnapshot | null;
}
export interface PurchaseCommand { eventId: string; sessionId: string; tierId: string; preview: TradePreview }
export interface PurchaseRecord extends PurchaseCommand {
  schemaVersion: 1; owner: string; origin: string; key: string; createdAt: number; retryAt: number; failures: number;
  state: 'UNKNOWN' | 'PENDING' | 'SUCCEEDED' | 'REJECTED'; result: PurchaseResult | null; errorCode: string;
}
export const purchaseRevision = ref(0)
export const purchaseKey = (owner: string) => 'ticketflow.purchase.v1.' + owner
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const date = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))
export function validPurchaseResult(value: unknown, command: PurchaseCommand): value is PurchaseResult {
  if (!value || typeof value !== 'object') return false
  const r = value as PurchaseResult
  if (!uuid(r.requestId) || !['ACCEPTED', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'REJECTED'].includes(r.state)) return false
  if (r.state === 'REJECTED') return typeof r.failureCode === 'string' && /^[A-Z_]{1,64}$/.test(r.failureCode)
    && r.orderId === null && r.snapshot === null && r.currentOrderStatus === null
    && ((r.acceptedAt === null && r.createDeadline === null) || (date(r.acceptedAt) && date(r.createDeadline)))
  if (!date(r.acceptedAt) || !date(r.createDeadline) || r.failureCode !== null) return false
  if (r.state !== 'SUCCEEDED') return r.orderId === null && r.snapshot === null && r.currentOrderStatus === null
  const s = r.snapshot
  return validId(r.orderId) && ['PENDING', 'PAID', 'CANCELLED', 'CLOSED', 'REFUNDED'].includes(r.currentOrderStatus ?? '')
    && !!s && s.schemaVersion === 1 && s.eventId === command.eventId && s.sessionId === command.sessionId && s.tierId === command.tierId
    && s.quantity === 1 && s.amountFen === command.preview.amountFen && s.unitPriceFen === command.preview.amountFen
    && typeof s.eventName === 'string' && typeof s.tierName === 'string' && date(s.startsAt)
}
export function pendingPurchase(r: PurchaseRecord | null) { return r?.state === 'UNKNOWN' || r?.state === 'PENDING' }
function unavailable() { return new ApiError('PURCHASE_STORAGE_ERROR', '抢票记录无法安全读取或保存，请保留浏览器数据并核查订单。') }
function valid(r: PurchaseRecord, owner: string) {
  const p = r?.preview
  return r?.schemaVersion === 1 && r.owner === owner && validId(owner) && r.origin === location.origin && uuid(r.key)
    && validId(r.eventId) && validId(r.sessionId) && validId(r.tierId)
    && Number.isSafeInteger(r.createdAt) && r.createdAt > 0 && Number.isSafeInteger(r.retryAt) && r.retryAt >= 0
    && Number.isSafeInteger(r.failures) && r.failures >= 0 && typeof r.errorCode === 'string' && r.errorCode.length <= 64
    && p && typeof p.eventName === 'string' && p.eventName.length <= 100 && typeof p.tierName === 'string' && p.tierName.length <= 100
    && date(p.startsAt) && Number.isSafeInteger(p.amountFen) && p.amountFen > 0 && p.amountFen <= 100_000_000
    && (r.result === null ? ['UNKNOWN', 'REJECTED'].includes(r.state)
      : validPurchaseResult(r.result, r) && r.state === (['SUCCEEDED', 'REJECTED'].includes(r.result.state) ? r.result.state : 'PENDING'))
}
export function readPurchase(owner: string): PurchaseRecord | null {
  try { const raw = localStorage.getItem(purchaseKey(owner)); if (raw === null) return null
    const r = JSON.parse(raw) as PurchaseRecord; if (!valid(r, owner)) throw unavailable(); return r
  } catch { throw unavailable() }
}
export function savePurchase(r: PurchaseRecord) {
  if (!valid(r, r.owner)) throw unavailable()
  try { const raw = JSON.stringify(r); localStorage.setItem(purchaseKey(r.owner), raw)
    if (localStorage.getItem(purchaseKey(r.owner)) !== raw) throw unavailable(); purchaseRevision.value++
  } catch { throw unavailable() }
}
export function dismissPurchase(owner: string) {
  const r = readPurchase(owner); if (!r || pendingPurchase(r)) throw unavailable()
  try { localStorage.removeItem(purchaseKey(owner)); purchaseRevision.value++ } catch { throw unavailable() }
}
