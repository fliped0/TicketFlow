<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import { useTradeStore } from '../stores/trade'
import { useAuthStore } from '../stores/auth'
import { errorText } from '../api/client'
import { dateTime, money } from '../utils/display'
import { operationLabels } from '../trade/journal'
const trade = useTradeStore(); const auth = useAuthStore(); const router = useRouter()
const error = ref(''); const now = ref(Date.now())
const wait = computed(() => Math.max(0, Math.ceil(((trade.record?.retryAt ?? 0) - now.value) / 1000)))
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => clearInterval(timer))
async function recover() {
  const owner = auth.user?.userId
  error.value = ''
  try {
    const result = await trade.execute()
    if (auth.user?.userId === owner) await router.push('/orders/' + result.orderId)
  } catch (failure) { if (auth.user?.userId === owner) error.value = errorText(failure) }
}
async function dismiss() { error.value = ''; try { await trade.dismiss() } catch (failure) { error.value = errorText(failure) } }
function rejectionMessage(code: string) {
  return ({ PURCHASE_LIMIT: '同一场次已有有效订单，请查看我的订单。', SOLD_OUT: '票档已售罄。', ASYNC_REQUIRED: '场次已改为异步抢票，请刷新场次后重新确认。', ORDER_EXPIRED: '订单已超过支付截止时间。', PAYMENT_SIMULATED_FAILURE: '模拟支付失败，可在窗口内重新确认。', REFUND_CLOSED: '场次已开场，无法模拟退款。', REFUND_SIMULATED_FAILURE: '模拟退款失败，订单仍为已支付，可在开场前重新确认。', NOT_FOUND: '资源不存在或无权访问。' } as Record<string, string>)[code] ?? '服务端已明确拒绝本次操作，请刷新相关信息。'
}
</script>

<template>
  <section v-if="trade.storageError || trade.record" class="trade-recovery" aria-label="交易结果与恢复">
    <template v-if="trade.storageError"><h2>交易记录需要核查</h2><p role="alert">{{ trade.storageError }}</p><ElButton @click="trade.refresh">重新读取记录</ElButton></template>
    <template v-else-if="trade.record">
      <h2>{{ trade.record.state === 'UNKNOWN' ? (trade.busy ? '正在处理已确认的操作' : '有一笔操作结果待确认') : trade.record.state === 'SUCCEEDED' ? '操作已完成' : '本次操作未成功' }}</h2>
      <p>{{ operationLabels[trade.record.operation] }} · {{ trade.record.preview.eventName }} · {{ trade.record.preview.tierName }} · {{ trade.record.operation === 'CANCEL' ? '原订单金额 ' : '' }}{{ money(trade.record.preview.amountFen) }}</p>
      <p class="muted">原始确认时间：{{ dateTime(new Date(trade.record.createdAt).toISOString()) }}</p>
      <template v-if="trade.record.state === 'UNKNOWN'"><p>请先恢复原操作结果。恢复可能完成你此前已经确认的{{ operationLabels[trade.record.operation] }}。</p><ElButton type="primary" :loading="trade.busy" :disabled="wait > 0" @click="recover">{{ wait > 0 ? `等待 ${wait} 秒后恢复` : '恢复原操作结果' }}</ElButton></template>
      <template v-else><p v-if="trade.record.state === 'REJECTED'">{{ rejectionMessage(trade.record.errorCode) }}</p><RouterLink v-if="trade.record.result" class="recovery-order-link" :to="'/orders/' + trade.record.result.orderId">查看订单</RouterLink><ElButton :disabled="trade.busy" @click="dismiss">收起已完成记录</ElButton></template>
    </template>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  </section>
</template>
