<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import TradeRecovery from './components/TradeRecovery.vue'
import { useAuthStore } from './stores/auth'
import { authNotice } from './stores/session'
import { errorText } from './api/client'
const auth = useAuthStore()
const route = useRoute(); const router = useRouter()
const restoreError = ref('')
async function restore() {
  restoreError.value = ''
  try { await auth.restore() } catch (error) { if (!authNotice.value) restoreError.value = errorText(error) }
}
onMounted(restore)
watch(() => auth.authenticated, value => { if (value) restoreError.value = '' })
watch(() => auth.user?.userId, (value, previous) => {
  if (!value && previous && route.meta.requiresAuth) void router.replace({ path: '/login', query: { redirect: route.fullPath } })
}, { flush: 'sync' })
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="site-header">
    <RouterLink class="brand" to="/" aria-label="TicketFlow 首页">
      <span class="brand-mark" aria-hidden="true">T</span>
      TicketFlow<span class="brand-caption">票务体验</span>
    </RouterLink>
    <nav class="header-nav" aria-label="主导航">
      <RouterLink to="/">发现活动</RouterLink>
      <RouterLink to="/orders">我的订单</RouterLink>
      <template v-if="auth.authenticated">
        <span class="account-name">{{ auth.user?.username }}</span>
        <button class="text-button" @click="auth.logout()">退出登录</button>
      </template>
      <template v-else><RouterLink to="/login">登录</RouterLink><RouterLink class="nav-signup" to="/register">注册</RouterLink></template>
    </nav>
  </header>
  <main id="main" tabindex="-1">
    <div v-if="authNotice || restoreError" class="auth-notice" role="status" aria-label="登录状态">{{ authNotice || restoreError }} <button v-if="restoreError" class="text-button" @click="restore">重试验证</button></div>
    <TradeRecovery v-if="auth.authenticated" />
    <RouterView />
  </main>
  <footer class="site-footer">
    <span>TicketFlow</span>
    <span>学习与演示项目 · 支付及退款均为模拟</span>
  </footer>
</template>
