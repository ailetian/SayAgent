<template>
  <div class="msg" :class="roleClass">
    <div class="msg-role">{{ isUser ? '你' : 'AI' }}</div>
    <div class="msg-bubble glass">
      <!-- 调用轨迹：流式进行中显示实时进度；结束后折叠为可回看的轨迹（对话日志铁律：KB/MCP 记录不得删） -->
      <div v-if="!isUser && steps.length" class="trace-wrap">
        <div v-if="streaming" class="steps">
          <div v-for="(s, i) in steps" :key="i" class="step" :class="s.status">
            <span class="step-ico">
              <span v-if="s.status === 'running'" class="spin" />
              <span v-else>✓</span>
            </span>
            <span class="step-label">{{ s.label }}</span>
          </div>
        </div>
        <details v-else class="trace">
          <summary>调用轨迹（{{ steps.length }} 条）</summary>
          <div v-for="(s, i) in steps" :key="i" class="trace-item" :class="s.kind">
            <div class="trace-head">
              <span class="tag">{{ s.kind === 'retrieval' ? '知识库' : 'MCP' }}</span>
              <span class="step-ico">✓</span>
              <span class="trace-title">{{ s.label }}</span>
            </div>
            <div v-if="traceDetail(s)" class="trace-detail">{{ traceDetail(s) }}</div>
          </div>
        </details>
      </div>
      <!-- 流式生成中：用纯文本（保留换行）即时渲染，保证逐字可见，避免半成品 markdown 阻塞显示；
           生成结束（streaming=false）后切回 MarkdownView 做最终排版。 -->
      <div v-if="!isUser && streaming" class="md streaming">{{ content }}</div>
      <MarkdownView v-else-if="!isUser" :content="content" />
      <div v-else class="md">{{ content }}</div>
    </div>
    <!-- 反馈（仅 AI 消息、非流式）：点赞 / 点踩 + 点踩原因弹层。沿用 DESIGN.md 设计令牌（青/琥珀、glass）。 -->
    <div v-if="!isUser && !streaming" class="fb-row">
      <button class="fb-btn" :class="{ active: rating === 'THUMBS_UP' }" title="有用" @click="onRate('THUMBS_UP')">👍</button>
      <button class="fb-btn" :class="{ active: rating === 'THUMBS_DOWN' }" title="没用" @click="onRate('THUMBS_DOWN')">👎</button>
      <div v-if="showReason" class="fb-reasons glass">
        <div class="fb-reasons-title">这条回复哪里不好？</div>
        <div class="fb-reasons-list">
          <button v-for="r in REASONS" :key="r" class="fb-reason" @click="pickReason(r)">{{ r }}</button>
        </div>
      </div>
    </div>
    <!-- Token 用量透传（仅 AI 消息、非流式，T11）：老消息/无记录显示「—」，不抛 NaN。 -->
    <div v-if="!isUser && !streaming" class="tok-row">
      <span>消耗 输入 {{ fmtTok(tokensIn) }} / 输出 {{ fmtTok(tokensOut) }} tokens</span>
      <span v-if="provider || model" class="tok-meta">· {{ provider }}{{ model ? ' · ' + model : '' }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import MarkdownView from './MarkdownView.vue'

const props = defineProps({
  role: { type: String, required: true }, // 'user' | 'assistant'（后端可能为 USER/ASSISTANT）
  content: { type: String, default: '' },
  // 进度步骤：[{ kind:'retrieval'|'tool', label, status:'running'|'done' }]
  steps: { type: Array, default: () => [] },
  // 是否正在流式生成（仅最后一条 assistant 消息为 true）：true 时正文按纯文本逐字渲染
  streaming: { type: Boolean, default: false },
  // 消息 id（后端 Message 主键，用于反馈归属）；流式生成中尚未落库时为 null
  messageId: { type: [Number, String], default: null },
  // 当前用户对该消息的评分回显：'THUMBS_UP' | 'THUMBS_DOWN' | ''（未评）
  myRating: { type: String, default: '' },
  // Token 用量（T11 透传）：输入/输出 token；老消息或 user 消息为 null → 显示「—」
  tokensIn: { type: Number, default: null },
  tokensOut: { type: Number, default: null },
  // 厂商 / 模型（T11 透传展示，非敏感，§7.11）
  provider: { type: String, default: '' },
  model: { type: String, default: '' }
})
const emit = defineEmits(['rate'])

// null → '—'，避免老消息/无记录时显示 NaN/undefined（T11 兼容要求）
function fmtTok(v) {
  return (v == null || Number.isNaN(v)) ? '—' : v
}

// 本地展示态：以 myRating 初始化，点击后即时高亮（乐观更新），由父组件调用接口落库。
const rating = ref(props.myRating || '')
// 父组件重新加载消息（如流式结束 refresh）会回灌 myRating，需同步本地态
watch(() => props.myRating, (v) => { rating.value = v || '' })

const showReason = ref(false)
const REASONS = ['答非所问', '信息过时', '检索不准', '其他']

// 点赞/点踩：再次点击同一按钮 → 取消（rating 置空）。点踩先弹原因层，选原因后才提交。
function onRate(target) {
  if (props.messageId == null) return
  if (target === 'THUMBS_UP') {
    if (rating.value === 'THUMBS_UP') {
      rating.value = ''
      emit('rate', { messageId: props.messageId, rating: null })
    } else {
      rating.value = 'THUMBS_UP'
      emit('rate', { messageId: props.messageId, rating: 'THUMBS_UP', reason: null })
    }
  } else {
    if (rating.value === 'THUMBS_DOWN') {
      rating.value = ''
      emit('rate', { messageId: props.messageId, rating: null })
    } else {
      showReason.value = true
    }
  }
}

function pickReason(reason) {
  showReason.value = false
  if (props.messageId == null) return
  rating.value = 'THUMBS_DOWN'
  emit('rate', { messageId: props.messageId, rating: 'THUMBS_DOWN', reason })
}

// 防御性归一化：无论外部传入 USER/ASSISTANT 还是 user/assistant，都收敛成小写，
// 保证 .msg.user / .msg.assistant 样式稳定命中。
const isUser = computed(() => {
  const r = (props.role || '').toString().trim().toLowerCase()
  return r === 'user' || r === 'human'
})
const roleClass = computed(() => (isUser.value ? 'user' : 'assistant'))

// 调用轨迹展开后的明细文案：知识库展示来源/相似度/片段；MCP 展示工具/入参/返回/状态。
function traceDetail(s) {
  if (!s) return ''
  if (s.kind === 'retrieval') {
    const parts = []
    if (s.docId) parts.push('来源文档：' + s.docId)
    if (s.score != null) parts.push('相似度：' + s.score)
    if (s.result) parts.push('片段：' + s.result)
    return parts.join('　·　')
  }
  if (s.kind === 'tool') {
    const parts = []
    if (s.toolName) parts.push('工具：' + s.toolName)
    if (s.args) parts.push('入参：' + s.args)
    if (s.result) parts.push('返回：' + s.result)
    if (s.success === false) parts.push('状态：不可用 / 失败')
    return parts.join('　·　')
  }
  return ''
}
</script>

<style scoped>
.msg { position: relative; display: flex; flex-direction: column; gap: 6px; max-width: 80%; width: fit-content; }
.msg.user { align-self: flex-end; align-items: flex-end; }
.msg.assistant { align-self: flex-start; align-items: flex-start; }
.msg-role { font-size: 12px; color: var(--muted); }
.msg-bubble { padding: 12px 16px; border-radius: 14px; line-height: 1.6; }
/* 流式纯文本态：保留换行与空格，逐字可见 */
.msg-bubble .streaming { white-space: pre-wrap; word-break: break-word; }
.msg.user .msg-bubble { border: 1px solid rgba(94, 234, 212, .35); background: rgba(94, 234, 212, .08); }
.msg.assistant .msg-bubble { border: 1px solid var(--line); }

/* 进度步骤面板 */
.steps {
  display: flex; flex-direction: column; gap: 6px;
  margin-bottom: 10px; padding: 8px 10px;
  background: rgba(94, 234, 212, .06);
  border: 1px solid rgba(94, 234, 212, .18);
  border-radius: 10px;
}
.step { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.step-ico {
  display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; flex: none;
  color: #5EEAD4; font-size: 12px; font-weight: 700;
}
.step-label { color: var(--text); opacity: .9; }
.step.running .step-label { color: #5EEAD4; }
.step.done .step-label { opacity: .7; }

/* 进行中旋转指示点 */
.spin {
  width: 11px; height: 11px; border-radius: 50%;
  border: 2px solid rgba(94, 234, 212, .35);
  border-top-color: #5EEAD4;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* 调用轨迹（结束后折叠回看） */
.trace {
  margin-bottom: 10px; padding: 8px 10px;
  background: rgba(94, 234, 212, .06);
  border: 1px solid rgba(94, 234, 212, .18);
  border-radius: 10px;
  font-size: 12.5px;
}
.trace > summary {
  cursor: pointer; color: #5EEAD4; opacity: .9;
  user-select: none; list-style: none; padding: 2px 0;
}
.trace > summary::-webkit-details-marker { display: none; }
.trace > summary::before { content: '▸ '; }
.trace[open] > summary::before { content: '▾ '; }
.trace-item { margin-top: 8px; padding-top: 8px; border-top: 1px dashed rgba(94, 234, 212, .15); }
.trace-item:first-of-type { border-top: none; margin-top: 6px; padding-top: 0; }
.trace-head { display: flex; align-items: center; gap: 8px; }
.trace-item .step-ico { color: #5EEAD4; font-weight: 700; }
.trace-title { color: var(--text); opacity: .9; }
.tag {
  font-size: 11px; padding: 1px 7px; border-radius: 999px;
  background: rgba(94, 234, 212, .14); color: #5EEAD4;
  border: 1px solid rgba(94, 234, 212, .3);
}
.trace-item.tool .tag { background: rgba(255, 180, 84, .14); color: #FFB454; border-color: rgba(255, 180, 84, .3); }
.trace-detail {
  margin-top: 5px; padding-left: 22px;
  color: var(--muted); line-height: 1.55; word-break: break-word;
  font-family: 'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
}

/* 反馈行（点赞 / 点踩）：沿用设计令牌，玻璃拟态小按钮 */
.fb-row {
  position: relative;
  display: flex; align-items: center; gap: 8px;
  padding-left: 4px;
}
.fb-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 26px; padding: 0;
  font-size: 14px; line-height: 1; cursor: pointer;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: var(--glass);
  color: var(--text); opacity: .7;
  transition: all .14s ease;
}
.fb-btn:hover { opacity: 1; background: var(--glass-strong); }
.fb-btn.active {
  opacity: 1;
  border-color: #5EEAD4;
  background: rgba(94, 234, 212, .14);
  color: #5EEAD4;
}
.fb-btn.active[title="没用"] {
  border-color: #FFB454;
  background: rgba(255, 180, 84, .14);
  color: #FFB454;
}

/* Token 用量行（T11）：沿用设计令牌，低调不喧宾夺主（§3.6） */
.tok-row {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  padding-left: 4px;
  font-size: 11.5px; color: var(--muted);
  font-family: 'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
}
.tok-meta { color: rgba(94, 234, 212, .75); }

/* 点踩原因弹层：glass 卡片，浮于按钮上方，避免被后续消息/滚动区裁切 */
.fb-reasons {
  position: absolute; bottom: calc(100% + 6px); left: 0; z-index: 20;
  min-width: 188px; padding: 10px 12px; border-radius: 12px;
  border: 1px solid var(--line);
}
.fb-reasons-title { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.fb-reasons-list { display: flex; flex-wrap: wrap; gap: 6px; }
.fb-reason {
  font-size: 12px; padding: 5px 10px; cursor: pointer; border-radius: 999px;
  border: 1px solid rgba(255, 180, 84, .35);
  background: rgba(255, 180, 84, .10);
  color: #FFB454;
  transition: all .14s ease;
}
.fb-reason:hover { background: rgba(255, 180, 84, .22); border-color: #FFB454; }

</style>
