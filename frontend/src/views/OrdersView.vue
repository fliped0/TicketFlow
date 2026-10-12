<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElPagination } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-pagination.css'
import { api, errorText } from '../api/client'
import type { Order, Page } from '../api/types'
import { dateTime, money, orderLabel } from '../utils/display'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore(); const route = useRoute(); const router = useRouter()
const items = ref<Order[]>([]); const total = ref(0); const page = ref(1); const status = ref('')
const loading = ref(false); const error = ref(''); let request: AbortController | undefined
const statuses = ['PENDING', 'PAID', 'CANCELLED', 'CLOSED', 'REFUNDED']
async function load() {
  request?.abort(); const current = new AbortController(); request = current
  items.value = []; total.value = 0; error.value = ''
  const owner = auth.user?.userId
  if (!owner) return
  loading.value = true
  status.value = typeof route.query.status === 'string' && statuses.includes(route.query.status) ? route.query.status : ''
  const n = Number(route.query.page)
  page.value = Number.isSafeInteger(n) && n > 0 && n < 1_000_000 ? n : 1
  const params = new URLSearchParams({ page: String(page.value), size: '10' })
  if (status.value) params.set('status', status.value)
  try {
    const result = await api<Page<Order>>(`/orders?${params}`, { authenticated: true, signal: current.signal })
    if (!current.signal.aborted && auth.user?.userId === owner) { items.value = result.items; total.value = result.total }
  } catch (failure) { if (!current.signal.aborted && auth.user?.userId === owner) error.value = errorText(failure) }
  finally { if (!current.signal.aborted) loading.value = false }
}
function filter(targetPage = 1) {
  const query: Record<string, string> = {}
  if (status.value) query.status = status.value
  if (targetPage > 1) query.page = String(targetPage)
  const target = router.resolve({ path: '/orders', query })
  if (target.fullPath === route.fullPath) void load()
  else void router.push(target.fullPath)
}
watch(() => route.fullPath, load, { immediate: true })
watch(() => auth.user?.userId, () => { request?.abort(); items.value = []; total.value = 0; error.value = '' }, { flush: 'sync' })
onUnmounted(() => request?.abort())
</script>

<template>
  <section class="orders-container"><div class="section-title"><div><p class="eyebrow">MY TICKETS</p><h1>我的订单</h1></div><ElButton :loading="loading" @click="load">刷新订单</ElButton></div>
    <form class="orders-filter" @submit.prevent="filter()"><label for="order-status">订单状态</label><select id="order-status" v-model="status" @change="filter()"><option value="">全部状态</option><option v-for="value in statuses" :key="value" :value="value">{{ orderLabel(value) }}</option></select><span class="muted">{{ total }} 笔订单</span></form>
    <div v-if="loading" class="state-panel" role="status">正在加载你的订单…</div>
    <div v-else-if="error" class="state-panel" role="alert"><p>{{ error }}</p><ElButton @click="load">重试</ElButton></div>
    <div v-else-if="!items.length" class="state-panel"><h2>暂无符合条件的订单</h2><RouterLink to="/">去发现活动</RouterLink></div>
    <div v-else class="order-list"><article v-for="item in items" :key="item.orderId" class="order-card"><div class="order-card-top"><span>订单号 {{ item.orderId }}</span><span class="order-status" :class="`status-${item.status.toLowerCase()}`">{{ orderLabel(item.status) }}</span></div><h2>{{ item.snapshot.eventName }}</h2><p>{{ dateTime(item.snapshot.startsAt) }}（北京时间） · {{ item.snapshot.city }} · {{ item.snapshot.venue }}</p><p>{{ item.snapshot.tierName }} · {{ item.quantity }} 张</p><div class="order-card-footer"><strong>{{ money(item.amountFen) }}</strong><RouterLink :to="'/orders/' + item.orderId">{{ item.status === 'PENDING' ? '查看订单并支付' : '查看订单详情' }} →</RouterLink></div></article></div>
    <ElPagination v-if="!loading && !error && total > 10" class="pager" layout="prev, pager, next" :total="total" :page-size="10" :current-page="page" @current-change="filter" />
  </section>
</template>
