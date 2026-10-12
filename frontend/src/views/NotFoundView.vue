<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="not-found">
    <p class="eyebrow">404</p>
    <h1>页面不存在</h1>
    <p>请检查地址，或返回首页。</p>
    <RouterLink class="home-link" to="/">返回首页</RouterLink>
  </section>
</template>
