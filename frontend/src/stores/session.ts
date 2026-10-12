import { ref } from 'vue'

const key = 'ticketflow.auth.v1'
function readToken(): string {
  try { return sessionStorage.getItem(key) ?? '' } catch { return '' }
}
export const accessToken = ref(readToken())
export const authNotice = ref('')
export function saveToken(token: string) {
  sessionStorage.setItem(key, token)
  accessToken.value = token
  authNotice.value = ''
}
export function clearToken(reason = '') {
  try { sessionStorage.removeItem(key) } catch { /* Always clear memory. */ }
  accessToken.value = ''
  authNotice.value = reason
}
