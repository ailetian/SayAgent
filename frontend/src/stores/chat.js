import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { listConversations, listMessages, streamChat, renameConversation, pinConversation, deleteConversation } from '../api/chat'
import { listAgents } from '../api/agent'

// F5 聊天页状态（Pinia）：会话列表 / 当前会话 / 消息流 / 流式 / keyset 历史。
// DOM 滚动等视图细节留在组件里，本 store 只管数据与动作。
export const useChatStore = defineStore('chat', () => {
  const conversations = ref([])
  const currentId = ref(null)
  const messages = ref([]) // { role, content, id? }
  const agents = ref([])
  const agentId = ref(null)
  const streaming = ref(false)
  const hasMore = ref(false)
  const oldestId = ref(null)
  const error = ref('')

  // 供组件触发滚动的轻量信号（避免深监听整条消息数组）
  const streamTick = ref(0) // 每收到一个 token 自增 → 滚到底
  const historyTick = ref(0) // 每次 keyset 前插自增 → 滚动位置保持
  const loadedTick = ref(0) // 每次整段加载（打开会话/流式后刷新）自增 → 滚到底

  let aborter = null

  const currentConversation = computed(
    () => conversations.value.find((c) => c.conversationId === currentId.value) || null
  )

  async function initAgents() {
    try {
      agents.value = await listAgents()
      if (agents.value.length && !agentId.value) {
        agentId.value = String(agents.value[0].id)
      }
    } catch (e) {
      // 拦截器已统一提示
    }
  }

  async function loadConvs() {
    conversations.value = await listConversations()
  }

  // 后端 MessageRole 枚举序列化后为 "USER"/"ASSISTANT"，前端统一归一化为小写 user/assistant，
  // 否则 role==='user' 判断与 .msg.user 样式全部失配（历史消息退化成统一左对齐+显示AI）。
  function normalizeRole(role) {
    const r = (role || '').toString().trim().toLowerCase()
    if (r === 'user' || r === 'human') return 'user'
    return 'assistant'
  }

  async function loadHistory(id, lastId) {
    const page = await listMessages(id, lastId)
    const items = (page.items || []).map((m) => ({ role: normalizeRole(m.role), content: m.content, id: m.id }))
    if (!lastId) {
      // 整段加载（打开会话 / 流式结束后刷新）
      messages.value = items
      loadedTick.value++
    } else {
      // keyset 前插：按 id 去重，避免重复
      const seen = new Set(messages.value.map((m) => m.id))
      messages.value = [...items.filter((m) => m.id == null || !seen.has(m.id)), ...messages.value]
      historyTick.value++
    }
    hasMore.value = !!page.hasMore
    oldestId.value = page.nextCursor ?? (items[0]?.id ?? null)
    return items
  }

  async function openConv(c) {
    currentId.value = c.conversationId
    // 还原该会话绑定的 Agent（避免继续对话时误用默认 Agent）
    if (c.agentId) agentId.value = c.agentId
    error.value = ''
    await loadHistory(c.conversationId)
  }

  async function loadEarlier() {
    if (!hasMore.value || oldestId.value == null || currentId.value == null) return
    await loadHistory(currentId.value, oldestId.value)
  }

  function newChat() {
    cancelStream()
    currentId.value = null
    messages.value = []
    error.value = ''
  }

  // 重命名会话（乐观更新标题后整体刷新，确保服务端置顶顺序生效）
  async function renameConv(conversationId, title) {
    const t = (title || '').trim()
    if (!t) return
    await renameConversation(conversationId, t)
    await loadConvs()
  }

  // 置顶 / 取消置顶（乐观更新 pinned 后整体刷新，确保服务端置顶顺序生效）
  async function pinConv(conversationId, pinned) {
    await pinConversation(conversationId, !!pinned)
    await loadConvs()
  }

  // 删除会话（若删的是当前会话则清空当前视图）
  async function deleteConv(conversationId) {
    await deleteConversation(conversationId)
    if (currentId.value === conversationId) {
      newChat()
    }
    await loadConvs()
  }

  function cancelStream() {
    if (aborter) aborter.abort()
    aborter = null
    streaming.value = false
  }

  async function send(text) {
    if (streaming.value || !text || !text.trim() || !agentId.value) return
    const content = text.trim()
    messages.value.push({ role: 'user', content })
    messages.value.push({ role: 'assistant', content: '', steps: [] })
    // ⚠️ Vue3 响应式陷阱：必须经由「响应式数组元素」(messages.value[idx]) 修改才能触发重渲染；
    // 若持有原始对象 ai 直接改 ai.content，会绕过代理 setter，导致流式 token 不刷新（气泡永久空白）。
    const aiIndex = messages.value.length - 1
    streaming.value = true
    error.value = ''
    aborter = new AbortController()
    try {
      await streamChat(
        { agentId: agentId.value, content, conversationId: currentId.value },
        {
          signal: aborter.signal,
          onToken: (chunk) => {
            messages.value[aiIndex].content += chunk
            streamTick.value++
          },
          onStep: (ev) => {
            const steps = messages.value[aiIndex].steps
            const label = ev.content || ''
            const status = ev.stepStatus || 'done'
            const kind = ev.kind || ''
            if (status === 'running') {
              steps.push({ kind, label, status: 'running' })
            } else {
              // 把同 kind 最近一条 running 标记为 done（更新文案）；找不到则追加
              let hit = -1
              for (let i = steps.length - 1; i >= 0; i--) {
                if (steps[i].kind === kind && steps[i].status === 'running') { hit = i; break }
              }
              if (hit >= 0) {
                steps[hit].status = 'done'
                if (label) steps[hit].label = label
              } else {
                steps.push({ kind, label, status: 'done' })
              }
            }
            streamTick.value++
          },
          onMeta: (ev) => {
            if (ev.conversationId) currentId.value = ev.conversationId
          },
          onError: (msg) => {
            messages.value[aiIndex].content += '\n\n[错误] ' + msg
          }
        }
      )
      // 流结束后刷新会话列表与历史（服务端已落库）
      await loadConvs()
      if (currentId.value != null) await loadHistory(currentId.value)
    } catch (e) {
      if (e && e.name === 'AbortError') {
        // 主动取消：保留已生成内容
      } else {
        const cur = messages.value[aiIndex].content
        messages.value[aiIndex].content = cur || ('出错了：' + (e && e.message ? e.message : e))
      }
    } finally {
      aborter = null
      streaming.value = false
    }
  }

  return {
    conversations,
    currentId,
    messages,
    agents,
    agentId,
    streaming,
    hasMore,
    oldestId,
    error,
    streamTick,
    historyTick,
    loadedTick,
    currentConversation,
    initAgents,
    loadConvs,
    openConv,
    loadHistory,
    loadEarlier,
    newChat,
    cancelStream,
    send,
    renameConv,
    pinConv,
    deleteConv
  }
})
