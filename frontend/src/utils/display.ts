const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
export function dateTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间待确认' : timeFormatter.format(date)
}
export function money(fen: number) {
  return Number.isSafeInteger(fen) && fen >= 0 ? `¥${(fen / 100).toFixed(2)}` : '价格待确认'
}
export function saleLabel(status: string) {
  return ({ ON_SALE: '售票中', SOLD_OUT: '已售罄', SALE_NOT_STARTED: '待开售', SALE_ENDED: '已停售', NOT_ON_SALE: '未上架' } as Record<string, string>)[status] ?? '状态待确认'
}
export function safeReturn(value: unknown) {
  if (typeof value !== 'string' || value.includes('\\') || /[\u0000-\u001f]/.test(value)) return '/'
  return /^\/(?:\?|$)/.test(value) || /^\/events\/[1-9]\d*(?:\?|$)/.test(value) || /^\/orders(?:\/[1-9]\d*)?(?:\?|$)/.test(value) || /^\/assistant(?:\?|$)/.test(value) ? value : '/'
}
export function orderLabel(status: string) {
  return ({ PENDING: '待支付', PAID: '已支付', CANCELLED: '已取消', CLOSED: '已关闭', REFUNDED: '已退款' } as Record<string, string>)[status] ?? '状态待确认'
}
export function credentialError(username: string, password: string) {
  if (!/^[A-Za-z0-9_]{4,32}$/.test(username)) return '账号须为 4–32 位字母、数字或下划线。'
  const length = [...password].length
  return length < 8 || length > 64 ? '密码须为 8–64 个字符，空格也计入密码。' : ''
}
