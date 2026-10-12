<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import { apiReply, errorText } from '../api/client'
import type { Order } from '../api/types'
import { dateTime, money, orderLabel } from '../utils/display'
import { useAuthStore } from '../stores/auth'
import { useTradeStore } from '../stores/trade'
import { validId, type TradeCommand } from '../trade/journal'
import TradeConfirm from '../components/TradeConfirm.vue'
const route = useRoute(); const auth = useAuthStore(); const trade = useTradeStore()
const order = ref<Order | null>(null); const loading = ref(false); const error = ref(''); const refreshError = ref('')
const now = ref(Date.now()); const serverOffset = ref(0)
const confirmation = ref<TradeCommand | null>(null); const submitError = ref('')
let request: AbortController | undefined; let timer: ReturnType<typeof setInterval> | undefined; let ticks = 0
const remaining = computed(() => order.value ? Math.max(0, Math.floor((Date.parse(order.value.expiresAt) - now.value - serverOffset.value) / 1000)) : 0)
const countdown = computed(() => `${Math.floor(remaining.value / 60).toString().padStart(2, '0')}:${(remaining.value % 60).toString().padStart(2, '0')}`)
const canPay = computed(() => order.value?.status === 'PENDING' && remaining.value > 0 && !trade.blocked)
const beforeStart = computed(() => !!order.value && now.value + serverOffset.value < Date.parse(order.value.snapshot.startsAt))
const canCancel = computed(() => order.value?.status === 'PENDING' && !trade.blocked)
const canRefund = computed(() => order.value?.status === 'PAID' && beforeStart.value && !trade.blocked)
const canSubmit = computed(() => !!confirmation.value && confirmation.value.targetId === order.value?.orderId
  && (confirmation.value.operation === 'PAY' ? canPay.value : confirmation.value.operation === 'CANCEL' ? canCancel.value : canRefund.value))
async function load(quiet = false) {
  request?.abort(); const current = new AbortController(); request = current
  const owner = auth.user?.userId; const id = route.params.id
  if (!owner) return
  if (!validId(id)) { order.value = null; error.value = '订单不存在或无权访问。'; return }
  if (!quiet) { loading.value = true; error.value = ''; order.value = null }
  refreshError.value = ''
  try {
    const reply = await apiReply<Order>(`/orders/${id}`, { authenticated: true, signal: current.signal })
    if (current.signal.aborted || auth.user?.userId !== owner || route.params.id !== id) return
    order.value = reply.data; now.value = Date.now(); serverOffset.value = reply.serverTime - now.value
  } catch (failure) {
    if (!current.signal.aborted && auth.user?.userId === owner) {
      if (quiet) refreshError.value = errorText(failure)
      else error.value = errorText(failure)
    }
  } finally { if (!current.signal.aborted) loading.value = false }
}
function prepare(operation: 'PAY' | 'CANCEL' | 'REFUND') {
  if (!order.value || !(operation === 'PAY' ? canPay.value : operation === 'CANCEL' ? canCancel.value : canRefund.value)) return
  submitError.value = ''
  confirmation.value = { operation, targetId: order.value.orderId, preview: {
    eventName: order.value.snapshot.eventName, tierName: order.value.snapshot.tierName,
    startsAt: order.value.snapshot.startsAt, amountFen: order.value.amountFen,
  } }
}
async function submit() {
  if (!confirmation.value || !canSubmit.value || !auth.user) return
  const owner = auth.user.userId; const id = confirmation.value.targetId
  submitError.value = ''
  try {
    await trade.execute(confirmation.value)
    if (auth.user?.userId === owner && route.params.id === id) { confirmation.value = null; await load() }
  } catch (failure) { if (auth.user?.userId === owner && route.params.id === id) { submitError.value = errorText(failure); void load(true) } }
}
watch(() => route.params.id, () => { confirmation.value = null; void load() }, { immediate: true })
watch(() => auth.user?.userId, () => { request?.abort(); order.value = null; confirmation.value = null; error.value = ''; refreshError.value = '' }, { flush: 'sync' })
watch(() => trade.record?.state, state => { if (state === 'SUCCEEDED' && trade.record?.result?.orderId === route.params.id) void load(true) })
onMounted(() => { timer = setInterval(() => {
  now.value = Date.now()
  if (++ticks % 10 === 0 && ['PENDING', 'PAID'].includes(order.value?.status ?? '') && !trade.busy && !confirmation.value && document.visibilityState === 'visible') void load(true)
}, 1000) })
onUnmounted(() => { request?.abort(); clearInterval(timer) })
</script>

<template>
  <section class="orders-container"><RouterLink class="back-link" to="/orders">← 返回我的订单</RouterLink><div class="section-title"><div><p class="eyebrow">YOUR TICKET</p><h1>订单详情</h1></div><ElButton :loading="loading" @click="load()">刷新订单状态</ElButton></div>
    <div v-if="loading" class="state-panel" role="status">正在加载订单…</div>
    <div v-else-if="error" class="state-panel" role="alert"><p>{{ error }}</p><ElButton @click="load()">重试</ElButton></div>
    <template v-else-if="order"><p v-if="refreshError" class="error-message" role="alert">{{ refreshError }} 当前展示的是上次查询结果。</p><div class="order-detail-layout"><article class="order-detail-card"><div class="order-card-top"><span>订单号 {{ order.orderId }}</span><span class="order-status" :class="`status-${order.status.toLowerCase()}`">{{ orderLabel(order.status) }}</span></div><h2>{{ order.snapshot.eventName }}</h2><dl class="order-fields"><dt>场次</dt><dd>{{ dateTime(order.snapshot.startsAt) }}（北京时间）</dd><dt>地点</dt><dd>{{ order.snapshot.city }} · {{ order.snapshot.venue }}</dd><dt>票档</dt><dd>{{ order.snapshot.tierName }}</dd><dt>数量</dt><dd>{{ order.quantity }} 张</dd><dt>订单金额</dt><dd>{{ money(order.amountFen) }}</dd><dt>创建时间</dt><dd>{{ dateTime(order.createdAt) }}</dd><dt>支付截止</dt><dd>{{ dateTime(order.expiresAt) }}</dd></dl><p class="muted">以上为下单时的成交快照。</p>
      <section v-if="order.payment" class="payment-record" aria-label="模拟支付记录"><h3>模拟支付记录</h3><p>支付编号：{{ order.payment.paymentId }}</p><p>金额：{{ money(order.payment.amountFen) }} · {{ dateTime(order.payment.paidAt) }}</p></section>
      <section v-if="order.refund" class="payment-record" aria-label="模拟退款记录"><h3>模拟退款记录</h3><p>退款编号：{{ order.refund.refundId }}</p><p>金额：{{ money(order.refund.amountFen) }} · {{ dateTime(order.refund.refundedAt) }}</p></section>
    </article><aside class="payment-panel"><p class="eyebrow">ORDER STATUS</p><h2>{{ orderLabel(order.status) }}</h2><template v-if="order.status === 'PENDING'"><p v-if="remaining > 0">支付剩余时间</p><p v-if="remaining > 0" class="countdown" aria-label="支付剩余时间">{{ countdown }}</p><p v-else class="muted">支付期限已到，请刷新确认服务端状态。</p><strong class="payment-amount">{{ money(order.amountFen) }}</strong><div class="order-actions"><ElButton type="primary" :disabled="!canPay" @click="prepare('PAY')">模拟支付</ElButton><ElButton :disabled="!canCancel" @click="prepare('CANCEL')">取消订单</ElButton></div><p class="muted">仅模拟全额支付，不发生真实扣款。取消未支付订单可释放占票，不涉及退款。操作资格以服务端检查为准。</p></template><template v-else-if="order.status === 'PAID'"><p class="muted">模拟支付已完成，可查看支付记录。</p><strong class="payment-amount">{{ money(order.amountFen) }}</strong><ElButton class="auth-submit" :disabled="!canRefund" @click="prepare('REFUND')">申请模拟退款</ElButton><p class="muted">{{ beforeStart ? '开场前支持全额模拟退款，成功后释放库存与同场购票资格，不发生真实资金交易。' : '场次已开场，模拟退款期限已结束。' }}</p></template><p v-else-if="order.status === 'REFUNDED'" class="muted">全额模拟退款已完成，可查看退款记录。此订单无需继续操作。</p><p v-else class="muted">此订单已结束，无需继续操作。</p></aside></div></template>
  </section>
  <TradeConfirm :open="!!confirmation" :operation="confirmation?.operation ?? 'PAY'" :preview="confirmation?.preview ?? null" :busy="trade.busy" :blocked="!canSubmit" :unresolved="trade.unresolved" :error="submitError" @close="confirmation = null" @approve="submit" />
</template>
