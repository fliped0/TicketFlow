import { expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import TradeConfirm from './TradeConfirm.vue'

test('a deadline rejection disables submission without suggesting unknown-result recovery', async () => {
  const wrapper = mount(TradeConfirm, {
    props: { open: true, operation: 'REFUND', preview: null, busy: false, blocked: true, unresolved: false, error: '场次已经开场，无法申请模拟退款。' },
    global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
  })
  try {
    expect(wrapper.get('[role="alert"]').text()).toContain('已经开场')
    expect(wrapper.text()).not.toContain('恢复入口')
    expect(wrapper.findAll('button').at(-1)!.attributes('disabled')).toBeDefined()
    await wrapper.setProps({ unresolved: true, error: '无法连接服务。' })
    expect(wrapper.text()).toContain('恢复入口')
  } finally { wrapper.unmount() }
})
