<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import { usePurchaseStore } from '../stores/purchase'
import { useAuthStore } from '../stores/auth'
import { errorText } from '../api/client'
import { dateTime, money } from '../utils/display'
const purchase = usePurchaseStore(); const auth = useAuthStore(); const route = useRoute(); const router = useRouter()
const now = ref(Date.now()); const error = ref(''); let timer: ReturnType<typeof setInterval> | undefined
const wait = computed(() => Math.max(0, Math.ceil(((purchase.record?.retryAt ?? 0) - now.value) / 1000)))
const title = computed(() => {
  if (purchase.storageError) return '抢票记录需要核查'
  const r = purchase.record
  if (r?.state === 'UNKNOWN') return '抢票受理结果待确认'
  if (r?.state === 'SUCCEEDED') return '抢票成功，订单已生成'
  if (r?.state === 'REJECTED') return '本次抢票未成功'
  return r?.result?.state === 'RETRY_WAIT' ? '正在等待服务恢复处理' : r?.result?.state === 'PROCESSING' ? '正在生成订单' : '已受理，正在排队处理'
})
const failedReason = computed(() => ({ SOLD_OUT: '票档已售罄，本次没有生成订单。', PURCHASE_LIMIT: '同一场次已有有效订单或抢票请求，请查看原记录。', PROCESSING_DEADLINE_EXCEEDED: '本次处理超时，没有生成订单，可以重新确认抢票。', ADMISSION_EXPIRED: '本次受理超时，没有生成订单。', NOT_ON_SALE: '活动已下架。', SALE_ENDED: '场次已停售。', SALE_NOT_STARTED: '场次尚未开售。', ACCOUNT_DISABLED: '账号已停用。', SYNC_REQUIRED: '场次已改为普通购票，请刷新场次后重新确认。', NOT_FOUND: '票档不存在。' } as Record<string, string>)[purchase.record?.errorCode ?? ''] ?? '服务端已明确拒绝本次请求，请刷新场次或核查订单。')
async function refresh() {
  const owner = auth.user?.userId; error.value = ''
  try { await purchase.run() } catch (failure) { if (auth.user?.userId === owner) error.value = errorText(failure) }
}
async function dismiss() { try { await purchase.dismiss(); error.value = '' } catch (failure) { error.value = errorText(failure) } }
watch(() => auth.user?.userId, () => { error.value = '' })
watch(() => purchase.record?.state, (state, before) => {
  const r = purchase.record
  if (state === 'SUCCEEDED' && before === 'PENDING' && r?.result?.orderId && route.path === '/events/' + r.eventId) void router.push('/orders/' + r.result.orderId)
})
onMounted(() => { timer = setInterval(() => {
  now.value = Date.now()
  // Poll only known accepted requests, only while visible; never automatically repeat a POST.
  if (document.visibilityState === 'visible' && navigator.onLine && purchase.record?.state === 'PENDING' && !purchase.busy && !purchase.storageError && wait.value === 0) void refresh()
}, 1000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <section v-if="purchase.record || purchase.storageError" class="trade-recovery purchase-recovery" aria-label="抢票结果与恢复" aria-live="polite">
    <h2>{{ title }}</h2>
    <template v-if="purchase.storageError"><p role="alert">{{ purchase.storageError }}</p><ElButton @click="purchase.refresh">重新读取抢票记录</ElButton></template>
    <template v-else-if="purchase.record">
      <p>{{ purchase.record.preview.eventName }} · {{ purchase.record.preview.tierName }} · {{ money(purchase.record.preview.amountFen) }}</p>
      <p class="muted">场次：{{ dateTime(purchase.record.preview.startsAt) }}</p>
      <template v-if="purchase.unresolved">
        <p v-if="purchase.record.state === 'UNKNOWN'">尚未确认服务端是否受理。请恢复原请求，恢复可能完成此前已确认的抢票；不要重新提交。</p>
        <template v-else><p>受理不代表抢票成功，页面会自动查询结果。刷新或重新登录同一账号后可继续查询。</p><p v-if="purchase.record.result?.createDeadline && now > Date.parse(purchase.record.result.createDeadline)" class="muted">预计处理时间已过，仍在核对最终结果，请勿重复提交。</p></template>
        <ElButton type="primary" :loading="purchase.busy" :disabled="wait > 0" @click="refresh">{{ wait > 0 ? `等待 ${wait} 秒` : purchase.record.state === 'UNKNOWN' ? '恢复原抢票请求' : '查询抢票结果' }}</ElButton>
      </template>
      <template v-else><p v-if="purchase.record.state === 'REJECTED'">{{ failedReason }}</p><RouterLink v-if="purchase.record.result?.orderId" class="recovery-order-link" :to="'/orders/' + purchase.record.result.orderId">查看订单并处理</RouterLink><ElButton :disabled="purchase.busy" @click="dismiss">收起抢票记录</ElButton></template>
      <details v-if="purchase.record.result" class="agent-source"><summary>抢票请求编号</summary><p>{{ purchase.record.result.requestId }}</p></details>
    </template>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
  </section>
</template>
