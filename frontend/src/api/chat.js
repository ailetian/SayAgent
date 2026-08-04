import request from '../utils/request'
import { getToken } from '../utils/token'
import { parseSSE } from '../utils/sse'

// 对话（后端 M6 /api/chat）
// 列表：普通 List<ConversationVO>
export function listConversations() {
  return request.get('/chat')
}

// 历史：keyset 分页，GET /api/chat/{conversationId}/messages?lastId=
// lastId 为「从此 id 之后开始」的游标；省略则取首页（最新一段）。
export function listMessages(conversationId, lastId) {
  const params = {}
  if (lastId != null) params.lastId = lastId
  return request.get(`/chat/${conversationId}/messages`, { params })
}

// 历史 keyset 加载（F5 验收点5）：传 lastId 拉更早消息，前插到顶部。
// 与 listMessages 同契约，单独导出以对齐文件清单命名。
export function loadHistory(conversationId, lastId) {
  return listMessages(conversationId, lastId)
}

// 重命名会话：PUT /api/chat/{conversationId}，body { title }
export function renameConversation(conversationId, title) {
  return request.put(`/chat/${conversationId}`, { title })
}

// 置顶 / 取消置顶：PUT /api/chat/{conversationId}/pin，body { pinned: boolean }
export function pinConversation(conversationId, pinned) {
  return request.put(`/chat/${conversationId}/pin`, { pinned })
}

// 删除会话：DELETE /api/chat/{conversationId}
export function deleteConversation(conversationId) {
  return request.delete(`/chat/${conversationId}`)
}

// SSE 流式对话（F5 验收点2/3）：POST + fetch + ReadableStream，带 Authorization，支持 AbortController 取消。
// ⚠ 禁止原生 EventSource(GET)（无法带 token）；禁止 token 塞 URL；禁止整段渲染而非逐 token。
// req: { agentId, content, conversationId? }；opts: { signal, onToken, onMeta, onDone, onError }
export async function streamChat(req, opts = {}) {
  const { agentId, content, conversationId } = req
  // 后端 ChatRequest 契约：字段名是 `message`（非 `content`），可选 `conversationId`（不传则新建会话）。
  const body = { agentId: String(agentId), message: content }
  if (conversationId != null) body.conversationId = String(conversationId)
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify(body),
    signal: opts.signal
  })
  if (!res.ok || !res.body) {
    const msg = res.status === 401 ? '登录态失效，请重新登录' : `请求失败(${res.status})`
    throw new Error(msg)
  }
  await parseSSE(res.body, opts)
  return res
}
