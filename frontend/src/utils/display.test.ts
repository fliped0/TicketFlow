import { expect, test } from 'vitest'
import { credentialError, dateTime, money, safeReturn } from './display'

test('credentials follow backend ASCII usernames and Unicode code-point password lengths', () => {
  expect(credentialError('user_123', '😀'.repeat(8))).toBe('')
  expect(credentialError('user_123', '😀'.repeat(65))).not.toBe('')
  expect(credentialError('user_123', '       x')).toBe('')
  expect(credentialError('用户1234', 'password')).not.toBe('')
})
test('authentication return path permits local catalogue pages and rejects external destinations', () => {
  expect(safeReturn('/events/123?session=456&tier=789')).toBe('/events/123?session=456&tier=789')
  for (const path of ['//evil.test', '/\\evil.test', 'https://evil.test', '/login', '/events/123/other', '/events/123\n']) expect(safeReturn(path)).toBe('/')
})
test('prices and timestamps preserve integer cents and display Beijing time', () => {
  expect(money(9901)).toBe('¥99.01')
  expect(money(0.5)).toBe('价格待确认')
  expect(dateTime('2026-10-12T00:30:00Z')).toContain('08:30')
})
