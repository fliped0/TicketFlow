<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="site-header">
    <RouterLink class="brand" to="/" aria-label="TicketFlow 首页">
      <span class="brand-mark" aria-hidden="true">T</span>
      TicketFlow<span class="brand-caption">票务体验</span>
    </RouterLink>
    <span class="environment-label">本机开发预览</span>
  </header>
  <main id="main" tabindex="-1"><RouterView /></main>
  <footer class="site-footer">
    <span>TicketFlow</span>
    <span>学习与演示项目 · 支付及退款均为模拟</span>
  </footer>
</template>
