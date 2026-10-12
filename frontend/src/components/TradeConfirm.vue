<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-overlay.css'
import type { TradePreview } from '../trade/journal'
import { dateTime, money } from '../utils/display'
const props = defineProps<{ open: boolean; operation: 'CREATE' | 'PAY'; preview: TradePreview | null; busy: boolean; blocked: boolean; error: string }>()
const emit = defineEmits<{ approve: []; close: [] }>()
const checked = ref(false)
watch(() => props.open, () => { checked.value = false })
</script>

<template>
  <ElDialog :model-value="open" :title="operation === 'CREATE' ? '确认下单' : '确认模拟支付'" width="min(520px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value => { if (!value && !busy) emit('close') }">
    <template v-if="preview"><p class="confirmation-event">{{ preview.eventName }}</p><dl class="confirmation-summary"><dt>场次（北京时间）</dt><dd>{{ dateTime(preview.startsAt) }}</dd><dt>票档</dt><dd>{{ preview.tierName }}</dd><dt>数量</dt><dd>1 张</dd><dt>{{ operation === 'CREATE' ? '订单金额' : '模拟支付金额' }}</dt><dd class="amount">{{ money(preview.amountFen) }}</dd></dl></template>
    <p class="muted">{{ operation === 'CREATE' ? '下单成功后将占用一张票，请在订单支付期限内完成模拟支付。' : '这是本项目的模拟支付，不会发生真实扣款。' }}</p>
    <label class="approval-checkbox"><input v-model="checked" type="checkbox" :disabled="busy" />{{ operation === 'CREATE' ? '我已核对活动、场次、票档和金额' : '我确认仅进行模拟支付，不发生真实扣款' }}</label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="error && blocked" class="muted">结果尚未确认时，关闭此窗口不会撤销操作。请使用页面上的恢复入口。</p>
    <template #footer><ElButton :disabled="busy" @click="emit('close')">{{ error ? '关闭' : '返回' }}</ElButton><ElButton type="primary" :loading="busy" :disabled="!checked || blocked" @click="emit('approve')">{{ operation === 'CREATE' ? '确认提交订单' : '确认模拟支付' }}</ElButton></template>
  </ElDialog>
</template>
