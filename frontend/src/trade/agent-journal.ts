import { ref } from 'vue'
import { ApiError } from '../api/client'
import { validConfirmation, terminalConfirmation, type Confirmation } from '../api/agent'
import { validId } from './journal'

export interface AgentRecord { schemaVersion: 1; owner: string; origin: string; attempted: boolean; card: Confirmation }
export const agentRevision = ref(0)
export const agentStorageKey = (owner: string) => 'ticketflow.agent-confirmation.v1.' + owner
function unavailable() { return new ApiError('AGENT_STORAGE_ERROR', '助手确认记录无法安全读取或保存，请保留浏览器数据并核查订单。') }
export function readAgentRecord(owner: string): AgentRecord | null {
  try {
    const raw = localStorage.getItem(agentStorageKey(owner)); if (raw === null) return null
    const record = JSON.parse(raw) as AgentRecord
    if (record.schemaVersion !== 1 || record.origin !== location.origin || record.owner !== owner || !validId(owner)
      || typeof record.attempted !== 'boolean' || !validConfirmation(record.card)) throw unavailable()
    return record
  } catch { throw unavailable() }
}
export function agentUnresolved(record: AgentRecord | null) {
  return !!record && !terminalConfirmation(record.card.state) && (record.attempted || ['UNKNOWN', 'EXECUTING'].includes(record.card.state))
}
export function saveAgentRecord(record: AgentRecord) {
  if (!validConfirmation(record.card)) throw unavailable()
  try {
    const raw = JSON.stringify(record); localStorage.setItem(agentStorageKey(record.owner), raw)
    if (localStorage.getItem(agentStorageKey(record.owner)) !== raw) throw unavailable()
    agentRevision.value++
  } catch { throw unavailable() }
}
export function clearAgentRecord(owner: string) {
  const current = readAgentRecord(owner)
  if (!current || !terminalConfirmation(current.card.state)) throw unavailable()
  try { localStorage.removeItem(agentStorageKey(owner)); agentRevision.value++ } catch { throw unavailable() }
}
