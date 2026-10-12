<script setup lang="ts">
import { ElTag } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-tag.css'
</script>

<template>
  <section class="welcome" aria-labelledby="welcome-title">
    <div class="welcome-copy">
      <p class="eyebrow">MEET YOUR NEXT MOMENT</p>
      <h1 id="welcome-title">让每次出发，<br />都有期待。</h1>
      <p class="lead">从一场演出、一段旋律开始，<br class="desktop-break" />找到值得亲临的下一刻。</p>
      <div class="readiness">
        <ElTag type="success" effect="plain" round>前端环境已就绪</ElTag>
        <p>这是工程启动页。活动浏览与购票功能将在后续开发中接入。</p>
      </div>
    </div>
    <div class="ticket-art" aria-hidden="true">
      <div class="ticket-orbit"></div>
      <div class="ticket-card">
        <div class="ticket-top"><span>TICKETFLOW</span><span>入场券</span></div>
        <div class="ticket-title">下一刻<br />在现场</div>
        <div class="ticket-bottom"><span>YOUR NEXT MOMENT</span><div class="barcode"></div></div>
      </div>
      <span class="art-caption">美好，值得亲临。</span>
    </div>
  </section>
</template>
