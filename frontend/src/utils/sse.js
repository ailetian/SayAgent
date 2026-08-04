// F5 / M6 T5：SSE 帧解析（§3.3 + M2 鉴权约束）。
// 后端 /api/chat/stream 逐帧发送 ChatEvent{event,content,...}，结束帧 event:"done"。
// 按 `data:` 行解析（兼容 [DONE] 哨兵），JSON 解析后分发回调。
// 本文件只负责把流解析成结构化事件，不含任何设计令牌/UI 逻辑。

/**
 * 读取 ReadableStream 并逐帧解析 SSE。
 * @param {ReadableStream<Uint8Array>} readableStream fetch 返回的 res.body
 * @param {object} [handlers]
 * @param {(chunk:string)=>void} [handlers.onToken] 收到 token 增量（逐字流式）
 * @param {(ev:object)=>void} [handlers.onMeta] 收到 meta 帧（含 conversationId 等）
 * @param {(ev:object)=>void} [handlers.onDone] 收到 done 帧
 * @param {(message:string)=>void} [handlers.onError] 收到 error 帧
 */
export async function parseSSE(readableStream, handlers = {}) {
  const { onToken, onMeta, onDone, onError } = handlers
  const reader = readableStream.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  // 终结帧：收到 done/error 即视为本次对话结束，立即结束解析（不等连接关闭）。
  // 否则若后端因落库/写日志异常漏发关闭信号，前端会一直阻塞、输入框被流式状态锁死。
  let terminal = false
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n')
      buf = parts.pop() || ''
      for (const line of parts) {
        const t = line.trim()
        if (!t.startsWith('data:')) continue
        const data = t.slice(5).trim()
        if (!data || data === '[DONE]') continue
        let ev
        try {
          ev = JSON.parse(data)
        } catch {
          continue
        }
        if (!ev || typeof ev !== 'object') continue
        if (ev.event === 'meta') onMeta && onMeta(ev)
        else if (ev.event === 'token') onToken && onToken(ev.content || '')
        else if (ev.event === 'done') { onDone && onDone(ev); terminal = true; break }
        else if (ev.event === 'error') { onError && onError(ev.message || '未知错误'); terminal = true; break }
      }
      if (terminal) break
    }
  } finally {
    try {
      reader.releaseLock()
    } catch {
      /* 已结束 */
    }
  }
}
