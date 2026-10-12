<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElPagination } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-pagination.css'
import { api, errorText } from '../api/client'
import type { Event, Page, Session, Tier } from '../api/types'
import { dateTime, money, saleLabel } from '../utils/display'
import { useAuthStore } from '../stores/auth'
import { useTradeStore } from '../stores/trade'
import TradeConfirm from '../components/TradeConfirm.vue'
import type { TradeCommand } from '../trade/journal'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore()
const trade = useTradeStore()
const confirmation = ref<TradeCommand | null>(null); const confirmationOwner = ref(''); const submitError = ref('')
function prepareOrder() {
  if (!event.value || !selectedTier.value || !selectedSession.value || !saleable.value || trade.blocked || !auth.user) return
  confirmationOwner.value = auth.user.userId; submitError.value = ''
  confirmation.value = { operation: 'CREATE', targetId: selectedTier.value.id, preview: {
    eventName: event.value.name, tierName: selectedTier.value.name, startsAt: selectedSession.value.startsAt, amountFen: selectedTier.value.priceFen,
  } }
}
async function submitOrder() {
  if (!confirmation.value || !auth.user || auth.user.userId !== confirmationOwner.value || trade.blocked) return
  const owner = auth.user.userId
  submitError.value = ''
  try {
    const result = await trade.execute(confirmation.value)
    if (auth.user?.userId === owner) { confirmation.value = null; await router.push('/orders/' + result.orderId) }
  } catch (failure) { if (auth.user?.userId === owner) submitError.value = errorText(failure) }
}
watch(() => auth.user?.userId, () => { confirmation.value = null; submitError.value = '' }, { flush: 'sync' })
const event = ref<Event | null>(null); const sessions = ref<Session[]>([]); const tiers = ref<Tier[]>([])
const selectedSession = ref<Session | null>(null); const selectedTier = ref<Tier | null>(null)
const sessionPage = ref(1); const sessionTotal = ref(0); const tierPage = ref(1); const tierTotal = ref(0)
const loading = ref(true); const tierLoading = ref(false); const error = ref(''); const tierError = ref('')
let eventRequest: AbortController | undefined; let tierRequest: AbortController | undefined
const saleable = computed(() => selectedTier.value?.saleStatus === 'ON_SALE' && selectedTier.value.available > 0)
async function load() {
  eventRequest?.abort(); tierRequest?.abort()
  const current = new AbortController(); eventRequest = current
  loading.value = true; error.value = ''; event.value = null; sessions.value = []; selectedSession.value = null; selectedTier.value = null; tiers.value = []
  const id = String(route.params.id)
  if (!/^[1-9]\d*$/.test(id)) { error.value = '活动不存在。'; loading.value = false; return }
  try {
    const [detail, result] = await Promise.all([
      api<Event>(`/events/${id}`, { signal: current.signal }),
      api<Page<Session>>(`/events/${id}/sessions?page=${sessionPage.value}&size=6`, { signal: current.signal }),
    ])
    if (current.signal.aborted) return
    event.value = detail; sessions.value = result.items; sessionTotal.value = result.total
    selectedSession.value = result.items.find(item => item.id === route.query.session) ?? result.items[0] ?? null
    tierPage.value = pageNumber(route.query.tp)
    await loadTiers()
  } catch (failure) { if (!current.signal.aborted) error.value = errorText(failure) }
  finally { if (!current.signal.aborted) loading.value = false }
}
async function loadTiers() {
  tierRequest?.abort(); const current = new AbortController(); tierRequest = current
  tiers.value = []; selectedTier.value = null; tierError.value = ''; tierTotal.value = 0
  if (!selectedSession.value) { tierLoading.value = false; return }
  tierLoading.value = true
  try {
    const result = await api<Page<Tier>>(`/sessions/${selectedSession.value.id}/tiers?page=${tierPage.value}&size=6`, { signal: current.signal })
    if (current.signal.aborted) return
    tiers.value = result.items; tierTotal.value = result.total
    selectedTier.value = result.items.find(item => item.id === route.query.tier && item.saleStatus === 'ON_SALE' && item.available > 0) ?? null
  } catch (failure) { if (!current.signal.aborted) tierError.value = errorText(failure) }
  finally { if (!current.signal.aborted) tierLoading.value = false }
}
function pageNumber(value: unknown) {
  const n = Number(value)
  return Number.isSafeInteger(n) && n > 0 && n < 1_000_000 ? n : 1
}
async function selectSession(item: Session) {
  selectedSession.value = item; tierPage.value = 1
  await router.replace({ query: { session: item.id, sp: String(sessionPage.value) } }); void loadTiers()
}
function selectTier(item: Tier) {
  if (item.saleStatus !== 'ON_SALE' || item.available < 1) return
  selectedTier.value = item
  void router.replace({ query: { session: selectedSession.value!.id, tier: item.id, sp: String(sessionPage.value), tp: String(tierPage.value) } })
}
async function changeSessionPage(page: number) {
  sessionPage.value = page
  await router.replace({ query: { sp: String(page) } })
  void load()
}
async function changeTierPage(page: number) {
  tierPage.value = page
  await router.replace({ query: { session: selectedSession.value!.id, sp: String(sessionPage.value), tp: String(page) } })
  void loadTiers()
}
watch(() => route.params.id, () => { sessionPage.value = pageNumber(route.query.sp); void load() }, { immediate: true })
watch(() => route.query.tier, id => {
  selectedTier.value = tiers.value.find(item => item.id === id && item.saleStatus === 'ON_SALE' && item.available > 0) ?? null
})
onUnmounted(() => { eventRequest?.abort(); tierRequest?.abort() })
</script>

<template>
  <div class="detail-container"><RouterLink class="back-link" to="/">← 返回活动列表</RouterLink>
    <div v-if="loading" class="state-panel" role="status">正在加载活动与票档…</div>
    <div v-else-if="error" class="state-panel" role="alert"><h1>活动暂时无法查看</h1><p>{{ error }}</p><ElButton @click="load">重试</ElButton></div>
    <template v-else-if="event">
      <section class="detail-header"><div class="detail-art" aria-hidden="true"><span>{{ event.category }}</span><strong>现场<br />见。</strong><small>TICKETFLOW</small></div><div class="detail-intro"><p class="eyebrow">{{ event.category }} · {{ event.city }}</p><h1>{{ event.name }}</h1><p>{{ event.city }} · {{ event.venue }}</p><p class="description">{{ event.description }}</p></div></section>
      <div class="booking-layout">
        <section class="booking-options" aria-labelledby="booking-title"><h2 id="booking-title">选择场次与票档</h2><p class="muted">所有时间为北京时间。每单一张，同一场次限一份有效订单。</p>
          <h3>01 · 场次</h3><p v-if="!sessions.length" class="muted">暂无可展示的场次。</p>
          <div class="session-grid"><button v-for="item in sessions" :key="item.id" class="choice-card" :class="{ selected: selectedSession?.id === item.id }" :aria-pressed="selectedSession?.id === item.id" @click="selectSession(item)"><strong>{{ dateTime(item.startsAt) }}</strong><span>{{ saleLabel(item.saleStatus) }}</span></button></div>
          <ElPagination v-if="sessionTotal > 6" class="pager" layout="prev, pager, next" :total="sessionTotal" :page-size="6" :current-page="sessionPage" @current-change="changeSessionPage" />
          <p v-if="selectedSession" class="muted">开售：{{ dateTime(selectedSession.saleStartAt) }}<br />停售：{{ dateTime(selectedSession.saleEndAt) }}</p>
          <h3>02 · 票档</h3><p v-if="tierLoading" role="status">正在加载票档…</p><div v-else-if="tierError" role="alert"><p class="error-message">{{ tierError }}</p><ElButton @click="loadTiers">重试票档</ElButton></div><p v-else-if="!tiers.length" class="muted">暂无票档。</p>
          <div v-else class="tier-grid"><button v-for="item in tiers" :key="item.id" class="choice-card tier-choice" :class="{ selected: selectedTier?.id === item.id }" :disabled="item.saleStatus !== 'ON_SALE' || item.available < 1" :aria-pressed="selectedTier?.id === item.id" @click="selectTier(item)"><strong>{{ item.name }}</strong><span class="tier-price">{{ money(item.priceFen) }}</span><span>{{ saleLabel(item.saleStatus) }} · 余 {{ item.available }} 张</span></button></div>
          <ElPagination v-if="tierTotal > 6" class="pager" layout="prev, pager, next" :total="tierTotal" :page-size="6" :current-page="tierPage" @current-change="changeTierPage" />
          <button class="text-button refresh-options" @click="loadTiers">刷新票档与库存</button>
        </section>
        <aside class="selection-summary" aria-label="选票摘要"><p class="eyebrow">YOUR TICKET</p><h2>选票摘要</h2><p>{{ event.name }}</p><dl><dt>场次</dt><dd>{{ selectedSession ? dateTime(selectedSession.startsAt) : '尚未选择' }}</dd><dt>票档</dt><dd>{{ selectedTier?.name ?? '尚未选择' }}</dd><dt>数量</dt><dd>1 张</dd></dl><div class="selection-amount"><span>票价</span><strong>{{ selectedTier ? money(selectedTier.priceFen) : '—' }}</strong></div>
          <RouterLink v-if="saleable && !auth.authenticated" class="primary-link" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录并保留选择</RouterLink>
          <ElButton v-else class="auth-submit" type="primary" :disabled="!saleable || trade.blocked" @click="prepareOrder">{{ saleable ? '确认选票并下单' : '请选择可售票档' }}</ElButton>
          <p class="muted summary-note">下单后请在订单支付期限内完成模拟支付。库存及限购以提交时的服务端判定为准。</p>
        </aside>
      </div>
    </template>
  </div>
  <TradeConfirm :open="!!confirmation" operation="CREATE" :preview="confirmation?.preview ?? null" :busy="trade.busy" :blocked="trade.blocked" :unresolved="trade.unresolved" :error="submitError" @close="confirmation = null" @approve="submitOrder" />
</template>
