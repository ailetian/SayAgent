<template>
  <div class="msg" :class="roleClass">
    <div class="msg-role">{{ isUser ? '你' : 'AI' }}</div>
    <div class="msg-bubble glass">
      <MarkdownView v-if="!isUser" :content="content" />
      <div v-else class="md">{{ content }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import MarkdownView from './MarkdownView.vue'

const props = defineProps({
  role: { type: String, required: true }, // 'user' | 'assistant'（后端可能为 USER/ASSISTANT）
  content: { type: String, default: '' }
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
.msg.user .msg-bubble { border: 1px solid rgba(94, 234, 212, .35); background: rgba(94, 234, 212, .08); }
.msg.assistant .msg-bubble { border: 1px solid var(--line); }
</style>
