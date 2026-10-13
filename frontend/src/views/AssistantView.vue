<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import { agentApi, terminalConfirmation, validConfirmation, type ChatReply, type QueryCard } from '../api/agent'
import { ApiError, errorText } from '../api/client'
import { useAgentStore } from '../stores/agent'
import { useAuthStore } from '../stores/auth'
import { readRecord } from '../trade/journal'
import { dateTime, money } from '../utils/display'
import TradeConfirm from '../components/TradeConfirm.vue'
import AgentDataCard from '../components/AgentDataCard.vue'

const auth = useAuthStore(); const agent = useAgentStore()
const message = ref(''); const sessionId = ref<string | null>(null); const sending = ref(false); const error = ref(''); const operationError = ref('')
const history = ref<{ question: string; reply: ChatReply }[]>([]); const confirmationOpen = ref(false); const now = ref(Date.now())
const approvalId = ref<string>()
let timer: ReturnType<typeof setInterval> | undefined; let generation = 0
const current = computed(() => agent.record?.card)
const preview = computed(() => current.value ? { eventName: current.value.snapshot.snapshot.eventName, tierName: current.value.snapshot.snapshot.tierName, startsAt: current.value.snapshot.snapshot.startsAt, amountFen: current.value.snapshot.amountFen } : null)
const canApprove = computed(() => current.value?.state === 'PENDING' && Date.parse(current.value.expiresAt) > now.value && !agent.busy && !agent.storageError)
const labels: Record<string, string> = { PENDING: '等待独立确认', EXECUTING: '正在执行，请稍后查询', UNKNOWN: '结果未知，请按原记录恢复', SUCCEEDED: '操作已完成', REJECTED: '本次操作被拒绝', EXPIRED: '确认已过期', CANCELLED: '确认已撤销' }
function validQuery(value: unknown): value is QueryCard {
  if (!value || typeof value !== 'object') return false
  const c = value as QueryCard
  return typeof c.tool === 'string' && !!c.data && typeof c.data === 'object' && !!c.source && typeof c.source.method === 'string' && typeof c.source.path === 'string'
}
async function ask(direct?: 'events' | 'orders' | 'rules') {
  if (sending.value || !auth.user) return
  const prompt = direct === 'events' ? '查看活动' : direct === 'orders' ? '查看我的订单' : direct === 'rules' ? '查看退款规则' : message.value.trim()
  if (!prompt || [...prompt].length > 2000) return
  const owner = auth.user.userId; const turn = ++generation; sending.value = true; error.value = ''
  try {
    let reply: ChatReply
    if (direct) {
      const tool = direct === 'events' ? 'search_events' : direct === 'orders' ? 'list_my_orders' : 'search_rules'
      const card = await agentApi<QueryCard>('/query', 'POST', { tool, arguments: direct === 'rules' ? { query: '退款规则' } : {} })
      if (!validQuery(card)) throw new ApiError('INVALID_RESPONSE', '查询结果无法核对。')
      reply = { sessionId: sessionId.value ?? '', modelMode: 'direct', message: '以下结果来自业务查询或已审核规则。', cards: [card] }
    } else {
      reply = await agentApi<ChatReply>('/chat', 'POST', { message: prompt, sessionId: sessionId.value })
      if (typeof reply.message !== 'string' || typeof reply.sessionId !== 'string' || !Array.isArray(reply.cards) || !reply.cards.every(validQuery)
        || (reply.confirmations !== undefined && (!Array.isArray(reply.confirmations) || !reply.confirmations.every(validConfirmation)))
        || (reply.citations !== undefined && (!Array.isArray(reply.citations) || !reply.citations.every(c => c && typeof c.excerpt === 'string' && typeof c.path === 'string')))) throw new ApiError('INVALID_RESPONSE', '助手返回内容无法核对。')
    }
    if (auth.user?.userId !== owner || generation !== turn) return
    if (!direct) { sessionId.value = reply.sessionId; message.value = '' }
    history.value.push({ question: prompt, reply }); if (history.value.length > 20) history.value.shift()
    // Selecting a card only reads its current status; never execute a chat instruction.
    if (reply.confirmations?.length === 1 && !agent.unresolved) await agent.select(reply.confirmations[0]!)
  } catch (failure) { if (auth.user?.userId === owner && generation === turn) error.value = errorText(failure) }
  finally { if (generation === turn) sending.value = false }
}
async function handle(kind: 'query' | 'execute' | 'recover' | 'revoke' | 'dismiss') {
  if (!auth.user) return
  const owner = auth.user.userId; operationError.value = ''
  try {
    if (kind === 'dismiss') await agent.dismiss()
    else await agent.action(kind, kind === 'execute' ? approvalId.value : undefined)
    if (auth.user?.userId === owner) confirmationOpen.value = false
  } catch (failure) { if (auth.user?.userId === owner) operationError.value = errorText(failure) }
}
async function review() {
  await handle('query')
  if (operationError.value || !canApprove.value || !auth.user) return
  try {
    if (readRecord(auth.user.userId)?.state === 'UNKNOWN') throw new ApiError('TRADE_UNRESOLVED', '请先恢复页面顶部的原购票交易。')
    approvalId.value = current.value?.confirmationId
    confirmationOpen.value = true
  } catch (failure) { operationError.value = errorText(failure) }
}
async function select(card: NonNullable<ChatReply['confirmations']>[number]) {
  operationError.value = ''; try { await agent.select(card) } catch (failure) { operationError.value = errorText(failure) }
}
async function newSession() {
  if (sending.value || !auth.user) return
  const owner = auth.user.userId; const turn = ++generation; error.value = ''; sending.value = true
  try {
  if (sessionId.value) {
    try { await agentApi('/sessions/' + sessionId.value, 'DELETE') }
    catch (failure) { if (!(failure instanceof ApiError && failure.code === 'SESSION_NOT_FOUND')) { if (auth.user?.userId === owner) error.value = errorText(failure); return } }
  }
  if (auth.user?.userId === owner) { sessionId.value = null; history.value = []; message.value = '' }
  } finally { if (generation === turn) sending.value = false }
}
watch(() => auth.user?.userId, () => { generation++; sending.value = false; history.value = []; sessionId.value = null; message.value = ''; error.value = ''; operationError.value = ''; confirmationOpen.value = false }, { flush: 'sync' })
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { generation++; clearInterval(timer) })
</script>

<template>
  <section class="assistant-container">
    <div class="section-title"><div><p class="eyebrow">TICKETFLOW ASSISTANT</p><h1>智能助手</h1><p class="muted">查询活动、订单与项目规则，也可以准备取消或模拟退款。</p></div><ElButton :disabled="sending" @click="newSession">新建会话</ElButton></div>
    <div class="assistant-layout"><div class="assistant-chat">
      <div class="agent-welcome"><h2>这次想了解什么？</h2><p>直接查询无需调用模型。自然语言提问会发送至已配置的模型服务，请勿输入密码或密钥。</p><div class="agent-actions"><ElButton :disabled="sending" @click="ask('events')">查询活动</ElButton><ElButton :disabled="sending" @click="ask('orders')">查询我的订单</ElButton><ElButton :disabled="sending" @click="ask('rules')">查询退款规则</ElButton></div></div>
      <div class="agent-history" aria-label="助手会话" aria-live="polite">
        <article v-for="(turn, index) in history" :key="index" class="agent-turn"><p class="agent-question">{{ turn.question }}</p><p class="agent-mode">{{ turn.reply.modelMode === 'gateway' ? '自然语言助手' : turn.reply.modelMode === 'demo' ? '固定指令演示 · 非真实模型' : '直接查询' }}</p><p class="agent-prose">{{ turn.reply.message }}</p>
          <AgentDataCard v-for="(card, n) in turn.reply.cards.filter(c => !c.tool.startsWith('prepare_'))" :key="n" :card="card" />
          <details v-if="turn.reply.citations?.length" class="agent-source"><summary>规则出处（{{ turn.reply.citations.length }}）</summary><blockquote v-for="citation in turn.reply.citations" :key="citation.citationId"><strong>{{ citation.ruleId }} · v{{ citation.version }}</strong><p>{{ citation.path }} · {{ citation.section }} · 第 {{ citation.line }} 行</p><p>{{ citation.excerpt }}</p></blockquote></details>
          <ElButton v-for="card in turn.reply.confirmations" :key="card.confirmationId" :disabled="agent.busy" @click="select(card)">查看订单 {{ card.orderId }} 的{{ card.operation === 'cancel' ? '取消' : '退款' }}确认</ElButton>
        </article>
        <p v-if="sending" role="status">正在查询，请稍候…</p>
      </div>
      <form class="agent-composer" @submit.prevent="ask()"><label for="assistant-message">你的问题</label><textarea id="assistant-message" v-model="message" rows="3" maxlength="2000" :disabled="sending" placeholder="例如：帮我查上海的活动；退款订单 123" /><p class="muted">每次请写明活动或订单编号，助手暂不记忆上一轮内容。演示指令：查活动、我的订单、规则 退款规则、取消 订单编号、退款 订单编号。</p><ElButton type="primary" native-type="submit" :loading="sending" :disabled="!message.trim()">发送问题</ElButton><p v-if="error" role="alert" class="error-message">{{ error }}</p></form>
    </div><aside class="agent-confirmation" aria-label="助手独立确认">
      <h2>操作确认</h2><p class="muted">聊天中的“确认”不会执行交易。请在这里核对，再通过独立窗口提交。</p>
      <template v-if="agent.storageError"><p role="alert" class="error-message">{{ agent.storageError }}</p><ElButton @click="agent.refresh">重新读取记录</ElButton></template>
      <template v-else-if="current"><p class="agent-state">{{ labels[current.state] }}</p><h3>{{ current.operation === 'cancel' ? '取消订单' : '全额模拟退款' }}</h3><p>{{ current.snapshot.snapshot.eventName }}</p><dl class="confirmation-summary"><dt>订单</dt><dd>{{ current.orderId }}</dd><dt>场次</dt><dd>{{ dateTime(current.snapshot.snapshot.startsAt) }}</dd><dt>票档</dt><dd>{{ current.snapshot.snapshot.tierName }}</dd><dt>退款金额</dt><dd>{{ money(current.amountFen) }}</dd><dt>首次确认截止</dt><dd>{{ dateTime(current.expiresAt) }}</dd></dl><p class="muted">{{ current.operation === 'cancel' ? '取消未支付订单并释放占票，不涉及退款。' : '开场前全额模拟退款，不发生真实资金交易。' }}</p>
        <p v-if="agent.unresolved" class="error-message">此前的确认可能已经执行。请先查询原记录，结果未知时再恢复；不要另建操作。</p>
        <p v-if="current.state === 'PENDING' && !canApprove" class="muted">确认已到期或暂不可执行，请查询最新状态。</p>
        <p v-if="current.result?.code && current.result.code !== 'OK'" class="muted">处理结果：{{ current.result.code }}，请查询订单当前状态。</p>
        <div class="order-actions"><ElButton :disabled="agent.busy" @click="handle('query')">查询确认结果</ElButton><ElButton v-if="current.state === 'PENDING'" type="primary" :disabled="!canApprove" @click="review">核对并确认操作</ElButton><ElButton v-if="current.state === 'UNKNOWN'" type="primary" :disabled="agent.busy" @click="handle('recover')">恢复原确认操作</ElButton><ElButton v-if="current.state === 'PENDING'" :disabled="agent.busy" @click="handle('revoke')">撤销此确认</ElButton><ElButton v-if="terminalConfirmation(current.state)" :disabled="agent.busy" @click="handle('dismiss')">收起确认记录</ElButton></div><RouterLink class="recovery-order-link" :to="'/orders/' + current.orderId">查看订单当前状态</RouterLink><details class="agent-source"><summary>确认记录编号</summary><p>{{ current.confirmationId }}</p></details>
      </template><p v-else class="muted">暂无待处理确认。可以在问题中写明“取消订单”或“退款订单”及编号。</p>
      <p v-if="operationError" class="error-message" role="alert">{{ operationError }}</p>
    </aside></div>
  </section>
  <TradeConfirm :open="confirmationOpen" :operation="current?.operation === 'refund' ? 'REFUND' : 'CANCEL'" :preview="preview" :busy="agent.busy" :blocked="!canApprove" :unresolved="agent.unresolved" :error="operationError" @close="confirmationOpen = false" @approve="handle('execute')" />
</template>
