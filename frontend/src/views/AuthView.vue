<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import { useAuthStore } from '../stores/auth'
import { errorText } from '../api/client'
import { credentialError, safeReturn } from '../utils/display'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore()
const registering = computed(() => route.path === '/register')
const username = ref(''); const password = ref(''); const confirmation = ref(''); const error = ref(''); const notice = ref('')
const returnTo = computed(() => safeReturn(route.query.redirect))
watch(() => route.path, () => { password.value = ''; confirmation.value = ''; error.value = '' })
async function submit() {
  if (auth.busy) return
  error.value = credentialError(username.value, password.value)
  if (error.value) return
  if (registering.value && confirmation.value !== password.value) { error.value = '两次输入的密码不一致。'; return }
  try {
    if (registering.value) {
      await auth.register(username.value, password.value)
      password.value = ''; confirmation.value = ''
      notice.value = '注册成功，请使用刚才的账号和密码登录。'
      await router.replace({ path: '/login', query: returnTo.value === '/' ? {} : { redirect: returnTo.value } })
    } else {
      await auth.login(username.value, password.value)
      password.value = ''
      await router.replace(returnTo.value)
    }
  } catch (failure) { error.value = errorText(failure) }
}
</script>

<template>
  <section class="auth-layout">
    <div class="auth-intro"><p class="eyebrow">YOUR NEXT MOMENT</p><h1>现场见。</h1><p>把期待变成一次出发。<br />你的下一张入场券，从这里开始。</p><div class="auth-decoration" aria-hidden="true">TICKET<br />FLOW</div></div>
    <div class="auth-card"><p class="eyebrow">{{ registering ? 'JOIN TICKETFLOW' : 'WELCOME BACK' }}</p><h2>{{ registering ? '创建账号' : '欢迎回来' }}</h2><p class="muted">{{ registering ? '注册你的 TicketFlow 账号。' : '登录后继续你的票务体验。' }}</p>
      <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
      <form @submit.prevent="submit" :aria-busy="auth.busy" novalidate>
        <div class="form-field"><label for="username">账号</label><input id="username" v-model="username" :disabled="auth.busy" autocomplete="username" maxlength="32" spellcheck="false" autocapitalize="none" required aria-describedby="username-hint" /><small id="username-hint">4–32 位字母、数字或下划线，大小写不区分。</small></div>
        <div class="form-field"><label for="password">密码</label><input id="password" v-model="password" :disabled="auth.busy" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" required aria-describedby="password-hint" /><small id="password-hint">8–64 个字符，密码中的空格会保留。</small></div>
        <div v-if="registering" class="form-field"><label for="confirmation">确认密码</label><input id="confirmation" v-model="confirmation" :disabled="auth.busy" type="password" autocomplete="new-password" required /></div>
        <p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <ElButton class="auth-submit" type="primary" native-type="submit" :loading="auth.busy">{{ registering ? '注册账号' : '登录' }}</ElButton>
      </form>
      <p class="auth-switch">{{ registering ? '已有账号？' : '还没有账号？' }} <RouterLink :to="{ path: registering ? '/login' : '/register', query: returnTo === '/' ? {} : { redirect: returnTo } }">{{ registering ? '去登录' : '立即注册' }}</RouterLink></p>
    </div>
  </section>
</template>
