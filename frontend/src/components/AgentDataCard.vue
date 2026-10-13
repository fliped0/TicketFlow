<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { QueryCard } from '../api/agent'
import { validId } from '../trade/journal'
import { dateTime, money, orderLabel, saleLabel } from '../utils/display'
const props = defineProps<{ card: QueryCard }>()
const titles: Record<string, string> = { search_events: '活动查询', get_event: '活动详情', list_sessions: '活动场次', list_tiers: '场次票档', list_my_orders: '我的订单', get_my_order: '订单查询', search_rules: '审核规则' }
const rows = computed(() => (Array.isArray(props.card.data.items) ? props.card.data.items : [props.card.data]).filter(item => item && typeof item === 'object') as Record<string, unknown>[])
const text = (value: unknown) => typeof value === 'string' || typeof value === 'number' ? String(value) : ''
function snapshot(row: Record<string, unknown>) { return row.snapshot && typeof row.snapshot === 'object' ? row.snapshot as Record<string, unknown> : {} }
function sources(row: Record<string, unknown>) { return Array.isArray(row.sources) ? row.sources.filter(s => s && typeof s === 'object') as Record<string, unknown>[] : [] }
</script>
<template>
  <article class="agent-data-card">
    <h3>{{ titles[card.tool] ?? '查询结果' }}</h3>
    <p v-if="card.data.notice" class="agent-prose">{{ text(card.data.notice) }}</p>
    <p v-if="!rows.length" class="muted">没有符合条件的结果。</p>
    <div v-for="(row, index) in rows" :key="index" class="agent-data-row">
      <strong>{{ text(row.name) || text(snapshot(row).eventName) || text(row.ruleId) }}</strong>
      <p v-if="row.city || row.venue">{{ text(row.city) }} {{ text(row.venue) }}</p>
      <p v-if="row.text" class="agent-prose">{{ text(row.text) }}</p>
      <details v-if="sources(row).length" class="agent-source"><summary>规则出处 · v{{ text(row.version) }}</summary><blockquote v-for="(source, n) in sources(row)" :key="n"><p>{{ text(source.path) }} · {{ text(source.section) }} · 第 {{ text(source.line) }} 行</p><p>{{ text(source.excerpt) }}</p></blockquote></details>
      <p v-if="row.startsAt">开场：{{ dateTime(text(row.startsAt)) }}</p>
      <p v-if="row.saleStatus">{{ saleLabel(text(row.saleStatus)) }}</p>
      <p v-if="typeof row.priceFen === 'number'">票价 {{ money(row.priceFen) }} · 余票 {{ text(row.available) }} 张</p>
      <p v-if="typeof row.amountFen === 'number'">{{ money(row.amountFen) }} · {{ orderLabel(text(row.status)) }}</p>
      <RouterLink v-if="validId(row.orderId)" :to="'/orders/' + row.orderId">查看订单 {{ row.orderId }}</RouterLink>
      <RouterLink v-else-if="['search_events', 'get_event'].includes(card.tool) && validId(row.id)" :to="'/events/' + row.id">查看活动 {{ row.id }}</RouterLink>
      <p v-else-if="row.id" class="muted">编号 {{ text(row.id) }}</p>
    </div>
    <p v-if="typeof card.data.total === 'number'" class="muted">共 {{ card.data.total }} 条 · 当前第 {{ card.data.page }} 页，更多结果可到活动或订单页面查看。</p>
    <details class="agent-source"><summary>查看数据来源</summary><p>{{ card.source.method === 'GET' ? '实时业务查询' : '本地审核资料' }} · {{ card.source.path }}</p><p v-if="card.source.queriedAt">查询时间：{{ dateTime(card.source.queriedAt) }}</p></details>
  </article>
</template>
