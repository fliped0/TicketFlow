export interface User { userId: string; username: string; role: 'USER' | 'ADMIN' }
export interface Token { accessToken: string; tokenType: string; expiresIn: number }
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Event {
  id: string; name: string; description: string; category: string; city: string;
  venue: string; status: string; version: number;
}
export interface Session {
  id: string; eventId: string; startsAt: string; saleStartAt: string;
  saleEndAt: string; saleStatus: string; version: number;
}
export interface Tier {
  id: string; sessionId: string; name: string; priceFen: number; available: number;
  capacity: number; saleStatus: string; refundPolicy: string; version: number;
}
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'CLOSED' | 'REFUNDED'
export interface OrderSnapshot {
  schemaVersion: number; eventId: string; eventName: string; city: string; venue: string;
  sessionId: string; startsAt: string; tierId: string; tierName: string;
  unitPriceFen: number; quantity: number; amountFen: number; refundPolicy: string;
}
export interface Order {
  orderId: string; status: OrderStatus; quantity: number; unitPriceFen: number; amountFen: number;
  snapshot: OrderSnapshot; createdAt: string; expiresAt: string;
  payment: { paymentId: string; amountFen: number; paidAt: string } | null;
  refund: { refundId: string; amountFen: number; refundedAt: string } | null;
}
export interface TradeResult {
  orderId: string; operationStatus: OrderStatus; currentOrderStatus: OrderStatus;
  amountFen: number; expiresAt: string; paymentId: string | null; refundId: string | null;
}
