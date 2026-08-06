<template>
  <div class="msg" :class="roleClass">
    <div class="msg-role">{{ isUser ? '你' : 'AI' }}</div>
    <div class="msg-bubble glass">
      <!-- 进度步骤（检索知识库 / 调用 MCP 工具），仅 AI 且存在 steps 时展示 -->
      <div v-if="!isUser && steps.length" class="steps">
        <div v-for="(s, i) in steps" :key="i" class="step" :class="s.status">
          <span class="step-ico">
            <span v-if="s.status === 'running'" class="spin" />
            <span v-else>✓</span>
          </span>
          <span class="step-label">{{ s.label }}</span>
        </div>
      </div>
      <!-- 流式生成中：用纯文本（保留换行）即时渲染，保证逐字可见，避免半成品 markdown 阻塞显示；
           生成结束（streaming=false）后切回 MarkdownView 做最终排版。 -->
      <div v-if="!isUser && streaming" class="md streaming">{{ content }}</div>
      <MarkdownView v-else-if="!isUser" :content="content" />
      <div v-else class="md">{{ content }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import MarkdownView from './MarkdownView.vue'

const props = defineProps({
  role: { type: String, required: true }, // 'user' | 'assistant'（后端可能为 USER/ASSISTANT）
  content: { type: String, default: '' },
  // 进度步骤：[{ kind:'retrieval'|'tool', label, status:'running'|'done' }]
  steps: { type: Array, default: () => [] },
  // 是否正在流式生成（仅最后一条 assistant 消息为 true）：true 时正文按纯文本逐字渲染
  streaming: { type: Boolean, default: false }
})

// 防御性归一化：无论外部传入 USER/ASSISTANT 还是 user/assistant，都收敛成小写，
// 保证 .msg.user / .msg.assistant 样式稳定命中。
const isUser = computed(() => {
  const r = (props.role || '').toString().trim().toLowerCase()
  return r === 'user' || r === 'human'
})
const roleClass = computed(() => (isUser.value ? 'user' : 'assistant'))
</script>

<style scoped>
.msg { display: flex; flex-direction: column; gap: 6px; max-width: 80%; width: fit-content; }
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
</style>
