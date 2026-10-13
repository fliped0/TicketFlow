<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { useAgentStore } from '../stores/agent'
const agent = useAgentStore(); const route = useRoute()
</script>
<template>
  <section v-if="(agent.storageError || agent.record) && route.path !== '/assistant'" class="trade-recovery" aria-label="助手确认记录">
    <h2>{{ agent.storageError ? '助手记录需要核查' : agent.unresolved ? '助手操作结果待确认' : '有一份助手确认记录' }}</h2>
    <p v-if="agent.storageError" role="alert">{{ agent.storageError }}</p>
    <p v-else>订单 {{ agent.record?.card.orderId }} · {{ agent.record?.card.operation === 'refund' ? '模拟退款' : '取消订单' }}</p>
    <RouterLink to="/assistant">前往智能助手查询和处理</RouterLink>
  </section>
</template>
