<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-overlay.css'
import { operationLabels, type TradePreview, type TradeOperation } from '../trade/journal'
import { dateTime, money } from '../utils/display'
const props = defineProps<{ open: boolean; operation: TradeOperation; preview: TradePreview | null; busy: boolean; blocked: boolean; unresolved: boolean; error: string }>()
const emit = defineEmits<{ approve: []; close: [] }>()
const checked = ref(false)
watch(() => [props.open, props.operation, props.preview], () => { checked.value = false })
const text = computed(() => ({
  CREATE: { amount: '订单金额', effect: '下单成功后将占用一张票，请在订单支付期限内完成模拟支付。', approval: '我已核对活动、场次、票档和金额', submit: '确认提交订单' },
  PAY: { amount: '模拟支付金额', effect: '这是本项目的模拟支付，不会发生真实扣款。', approval: '我确认仅进行模拟支付，不发生真实扣款', submit: '确认模拟支付' },
  CANCEL: { amount: '原订单金额', effect: '取消未支付订单后将释放占用的票与同场购票资格。订单未支付，不涉及退款（退款金额 ¥0.00）。已到支付期限的订单将关闭。', approval: '我确认取消此未支付订单并释放占票', submit: '确认取消订单' },
  REFUND: { amount: '模拟退款金额', effect: '开场前可申请全额模拟退款。成功后将释放库存与同场购票资格；不发生真实资金退回。资格以服务端检查为准。', approval: '我确认全额模拟退款，不发生真实资金交易', submit: '确认模拟退款' },
})[props.operation])
</script>

<template>
  <ElDialog :model-value="open" :title="'确认' + operationLabels[operation]" width="min(520px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value => { if (!value && !busy) emit('close') }">
    <template v-if="preview"><p class="confirmation-event">{{ preview.eventName }}</p><dl class="confirmation-summary"><dt>场次（北京时间）</dt><dd>{{ dateTime(preview.startsAt) }}</dd><dt>票档</dt><dd>{{ preview.tierName }}</dd><dt>数量</dt><dd>1 张</dd><dt>{{ text.amount }}</dt><dd class="amount">{{ money(preview.amountFen) }}</dd></dl></template>
    <p class="muted">{{ text.effect }}</p>
    <label class="approval-checkbox"><input v-model="checked" type="checkbox" :disabled="busy" />{{ text.approval }}</label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="error && unresolved" class="muted">结果尚未确认时，关闭此窗口不会撤销操作。请使用页面上的恢复入口。</p>
    <template #footer><ElButton :disabled="busy" @click="emit('close')">{{ error ? '关闭' : '返回' }}</ElButton><ElButton type="primary" :loading="busy" :disabled="!checked || blocked" @click="emit('approve')">{{ text.submit }}</ElButton></template>
  </ElDialog>
</template>
