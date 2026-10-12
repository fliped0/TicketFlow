<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElPagination } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-pagination.css'
import { api, errorText } from '../api/client'
import type { Event, Page } from '../api/types'

const route = useRoute(); const router = useRouter()
const events = ref<Event[]>([]); const total = ref(0); const page = ref(1)
const loading = ref(true); const error = ref(''); const keyword = ref(''); const city = ref(''); const category = ref('')
let request: AbortController | undefined
function value(name: string) { const v = route.query[name]; return typeof v === 'string' ? v : '' }
async function load() {
  request?.abort(); const current = new AbortController(); request = current
  loading.value = true; error.value = ''; events.value = []; total.value = 0
  keyword.value = value('keyword'); city.value = value('city'); category.value = value('category')
  const suppliedPage = Number(value('page'))
  page.value = Number.isSafeInteger(suppliedPage) && suppliedPage > 0 && suppliedPage <= 1_000_000 ? suppliedPage : 1
  const params = new URLSearchParams({ page: String(page.value), size: '9' })
  for (const [key, text] of Object.entries({ keyword: keyword.value, city: city.value, category: category.value })) if (text) params.set(key, text)
  try {
    const data = await api<Page<Event>>(`/events?${params}`, { signal: current.signal })
    if (!current.signal.aborted) { events.value = data.items; total.value = data.total }
  } catch (failure) { if (!current.signal.aborted) error.value = errorText(failure) }
  finally { if (!current.signal.aborted) loading.value = false }
}
function search(targetPage = 1) {
  const query: Record<string, string> = {}
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (city.value.trim()) query.city = city.value.trim()
  if (category.value) query.category = category.value
  if (targetPage > 1) query.page = String(targetPage)
  const target = router.resolve({ path: '/', query })
  if (target.fullPath === route.fullPath) void load()
  else void router.push(target.fullPath)
}
function reset() { keyword.value = ''; city.value = ''; category.value = ''; search() }
watch(() => route.fullPath, load, { immediate: true })
onUnmounted(() => request?.abort())
</script>

<template>
  <section class="catalog-hero"><div><p class="eyebrow">MEET YOUR NEXT MOMENT</p><h1>让每次出发，<br />都有期待。</h1><p class="lead">找到值得亲临的下一刻。</p></div><div class="hero-stamp" aria-hidden="true"><span>LIVE IN<br />THE MOMENT</span><small>TICKETFLOW · 现场见</small></div></section>
  <section class="catalog-section" aria-labelledby="catalog-title">
    <div class="section-title"><div><p class="eyebrow">DISCOVER</p><h2 id="catalog-title">发现活动</h2></div><span class="muted">{{ total }} 个活动</span></div>
    <form class="search-bar" @submit.prevent="search()">
      <div class="form-field"><label for="keyword">活动名称</label><input id="keyword" v-model="keyword" placeholder="搜索你期待的活动" maxlength="100" type="search" /></div>
      <div class="form-field"><label for="city">城市</label><input id="city" v-model="city" placeholder="全部城市" maxlength="64" /></div>
      <div class="form-field"><label for="category">分类</label><input id="category" v-model="category" list="categories" placeholder="全部分类" maxlength="32" /><datalist id="categories"><option value="音乐" /><option value="戏剧" /><option value="展览" /><option value="music" /></datalist></div>
      <ElButton type="primary" native-type="submit" :loading="loading">搜索活动</ElButton><button type="button" class="text-button" @click="reset">重置</button>
    </form>
    <div v-if="loading" class="state-panel" role="status">正在寻找值得期待的活动…</div>
    <div v-else-if="error" class="state-panel" role="alert"><h3>活动暂时无法加载</h3><p>{{ error }}</p><ElButton @click="load">重试</ElButton></div>
    <div v-else-if="!events.length" class="state-panel"><h3>暂时没有找到活动</h3><p>试试其他关键词、城市或分类。</p><ElButton @click="reset">清除筛选</ElButton></div>
    <div v-else class="event-grid">
      <RouterLink v-for="(event, index) in events" :key="event.id" class="event-card" :to="`/events/${event.id}`">
        <div class="event-art" :class="`art-${index % 3}`" aria-hidden="true"><span class="art-category">{{ event.category }}</span><span class="art-word">{{ event.category === '音乐' ? 'LIVE' : event.category === '戏剧' ? 'STAGE' : 'MOMENT' }}</span><span class="art-location">{{ event.city }} · {{ event.venue }}</span></div>
        <div class="event-copy"><span class="category-label">{{ event.category }}</span><h3>{{ event.name }}</h3><p>{{ event.city }} · {{ event.venue }}</p><div class="event-card-footer"><span>查看场次与票档</span><span aria-hidden="true">↗</span></div></div>
      </RouterLink>
    </div>
    <ElPagination v-if="!loading && !error && total > 9" class="pager" background layout="prev, pager, next" :total="total" :page-size="9" :current-page="page" @current-change="search" />
  </section>
</template>
