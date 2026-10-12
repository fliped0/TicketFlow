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
