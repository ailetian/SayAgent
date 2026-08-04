<template>
  <div class="chat">
    <div class="ambient" />
    <!-- 会话列表 -->
    <aside class="chat-side glass">
      <div class="chat-side-head">
        <span class="page-title" style="font-size:15px">对话</span>
        <button class="btn-ghost" style="padding:6px 12px" @click="onNewChat">+ 新建</button>
      </div>
      <div class="conv-list">
        <div
          v-for="c in chat.conversations"
          :key="c.conversationId"
          class="conv-item"
          :class="{ active: c.conversationId === chat.currentId }"
          @click="editingId !== c.conversationId && chat.openConv(c)"
        >
          <!-- 重命名编辑态 -->
          <input
            v-if="editingId === c.conversationId"
            ref="editInput"
            v-model="editText"
            class="conv-edit"
            maxlength="80"
            @keyup.enter="commitRename(c)"
            @keyup.esc="cancelRename"
            @blur="commitRename(c)"
          />
          <!-- 普通态 -->
          <template v-else>
            <div class="conv-main">
              <div class="conv-title">
                <span v-if="c.pinned" class="pin-flag" title="已置顶">📌</span>{{ c.title || ('对话 #' + c.conversationId) }}
              </div>
              <div class="muted" style="font-size:11px">{{ c.agentId || '—' }}</div>
            </div>
            <div class="conv-actions">
              <button
                class="icon-btn"
                :title="c.pinned ? '取消置顶' : '置顶'"
                @click.stop="chat.pinConv(c.conversationId, !c.pinned)"
              >{{ c.pinned ? '📍' : '📌' }}</button>
              <button class="icon-btn" title="重命名" @click.stop="startRename(c)">✏️</button>
              <button class="icon-btn danger" title="删除" @click.stop="onDelete(c)">🗑️</button>
            </div>
          </template>
        </div>
        <div v-if="!chat.conversations.length" class="muted" style="padding:16px;font-size:13px">暂无对话</div>
      </div>
    </aside>

    <!-- 主区 -->
    <main class="chat-main">
      <div class="chat-bar">
        <el-select v-model="chat.agentId" placeholder="选择 Agent" style="width:240px" :disabled="chat.streaming">
          <el-option v-for="a in chat.agents" :key="a.id" :label="a.name" :value="String(a.id)" />
        </el-select>
        <span class="muted" style="font-size:12px">{{ chat.streaming ? '生成中…' : '' }}</span>
      </div>

      <div ref="scroll" class="chat-scroll" @scroll="onScroll">
        <div ref="list" class="chat-list">
          <div v-if="chat.messages.length" class="chat-history">
            <button
              v-if="chat.hasMore"
              class="link-a chat-loadmore"
              :disabled="chat.streaming"
              @click="onLoadEarlier"
            >加载更早消息</button>
            <span v-else class="muted" style="font-size:12px">没有更多了</span>
          </div>

          <MessageBubble
            v-for="(m, i) in chat.messages"
            :key="m.id != null ? m.id : 'm' + i"
            :role="m.role"
            :content="m.content"
          />

          <div v-if="!chat.messages.length" class="muted chat-empty">选择 Agent 并开始对话。</div>
        </div>
      </div>

      <div class="chat-input glass">
        <textarea
          v-model="draft"
          :disabled="chat.streaming"
          placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
          @keydown.enter.exact.prevent="onSend"
        />
        <button class="btn-grad" :disabled="chat.streaming || !draft.trim() || !chat.agentId" @click="onSend">发送</button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import MessageBubble from './chat/MessageBubble.vue'
import { useChatStore } from '../stores/chat'

const chat = useChatStore()
const draft = ref('')
const scroll = ref(null)
const list = ref(null)
const atBottom = ref(true) // 用户当前是否贴近底部（决定是否自动跟随）
let ro = null // ResizeObserver 实例
let loadingEarlier = false // 防止 keyset 前插期间被自动跟随打断
const NEAR = 80 // 距底部 < 80px 视为「在底部」

// 会话项内联重命名状态
const editingId = ref(null)
const editText = ref('')
const editInput = ref(null)

function startRename(c) {
  editingId.value = c.conversationId
  editText.value = c.title || ''
  nextTick(() => {
    const el = editInput.value
    if (el) {
      el.focus()
      el.select && el.select()
    }
  })
}

async function commitRename(c) {
  // 避免 blur 与 enter 重复触发（enter 已先置空 editingId，blur 时不再处理）
  if (editingId.value !== c.conversationId) return
  const id = c.conversationId
  const text = editText.value
  editingId.value = null
  editText.value = ''
  await chat.renameConv(id, text)
}

function cancelRename() {
  editingId.value = null
  editText.value = ''
}

async function onDelete(c) {
  try {
    await ElMessageBox.confirm(
      `确定删除对话「${c.title || '该对话'}」吗？此操作不可恢复。`,
      '删除对话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await chat.deleteConv(c.conversationId)
  } catch (e) {
    // 用户取消，不处理
  }
}

// —— 自动滚到底部：仅当用户已在底部附近或正在流式时跟随，避免打断看历史 ——
function isNearBottom() {
  const el = scroll.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR
}

function scrollToBottom(behavior = 'auto') {
  const el = scroll.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior })
}

function maybeAutoScroll() {
  if (loadingEarlier) return
  if (chat.streaming || atBottom.value) scrollToBottom('auto')
}

async function onSend() {
  if (chat.streaming) return
  const text = draft.value
  draft.value = ''
  atBottom.value = true // 主动发消息，应跟随到最新
  await chat.send(text)
  nextTick(maybeAutoScroll)
}

function onNewChat() {
  chat.newChat()
  draft.value = ''
  atBottom.value = true
  nextTick(maybeAutoScroll)
}

// keyset 前插：保持滚动位置不跳页（验收点5）
async function onLoadEarlier() {
  if (!scroll.value || loadingEarlier) return
  loadingEarlier = true
  const el = scroll.value
  const prevHeight = el.scrollHeight
  await chat.loadEarlier()
  await nextTick()
  el.scrollTop = el.scrollHeight - prevHeight + el.scrollTop
  loadingEarlier = false
}

function onScroll() {
  if (!scroll.value) return
  atBottom.value = isNearBottom()
  if (scroll.value.scrollTop < 40 && chat.hasMore && !chat.streaming) {
    onLoadEarlier()
  }
}

// 流式 token / 整段加载完成 → 滚到底（ResizeObserver 兜底内容高度变化）
watch(() => chat.streamTick, maybeAutoScroll)
watch(() => chat.loadedTick, () => {
  atBottom.value = true
  maybeAutoScroll()
})

onMounted(async () => {
  await chat.initAgents()
  chat.loadConvs()
  if (list.value) {
    // 监听消息区高度变化（含异步 markdown/代码块渲染完成后的撑高），自动跟随到底部
    ro = new ResizeObserver(() => maybeAutoScroll())
    ro.observe(list.value)
  }
})

onUnmounted(() => {
  if (ro) ro.disconnect()
  // 验收点6：组件卸载中断 fetch，后端可见取消（AbortController.abort）
  chat.cancelStream()
})
</script>

<style scoped>
.chat { position: relative; flex: 1; min-height: 0; display: flex; gap: 16px; padding: 16px; }
.chat-side { width: 240px; flex: none; display: flex; flex-direction: column; padding: 14px; z-index: 1; }
.chat-side-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.conv-list { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
.conv-item {
  display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-radius: 10px; cursor: default;
  background: transparent; border: 1px solid transparent; color: var(--text);
  transition: background .15s, border-color .15s;
}
.conv-item:hover { background: var(--glass); }
.conv-item.active { background: var(--glass-strong); border-color: var(--line); }
.conv-main { flex: 1; min-width: 0; cursor: pointer; }
.conv-title { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pin-flag { margin-right: 2px; filter: grayscale(0); }
.conv-actions { display: none; flex: none; gap: 2px; }
.conv-item:hover .conv-actions { display: flex; }
.icon-btn {
  border: 0; background: transparent; cursor: pointer; font-size: 13px; line-height: 1;
  padding: 4px; border-radius: 6px; color: var(--text); opacity: .65; transition: background .12s, opacity .12s;
}
.icon-btn:hover { background: var(--glass-strong); opacity: 1; }
.icon-btn.danger:hover { background: rgba(255, 80, 80, .18); color: #ff5b5b; }
.conv-edit {
  flex: 1; min-width: 0; background: var(--input-bg); border: 1px solid var(--line); border-radius: 6px;
  color: var(--text); font-size: 13px; padding: 6px 8px; outline: 0; font-family: inherit;
}
.conv-edit:focus { border-color: var(--accent); }

.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; z-index: 1; }
.chat-bar { display: flex; align-items: center; gap: 12px; padding: 4px 4px 12px; }
.chat-scroll { flex: 1; min-height: 0; overflow: auto; padding: 8px 4px; }
.chat-list { display: flex; flex-direction: column; gap: 16px; }
.chat-empty { text-align: center; margin-top: 40px; }
.chat-history { display: flex; justify-content: center; align-items: center; padding: 4px 0 8px; }
.chat-loadmore { font-size: 13px; }

.chat-input { display: flex; gap: 12px; align-items: flex-end; padding: 12px; margin-top: 12px; z-index: 1; }
.chat-input textarea {
  flex: 1; background: transparent; border: 0; outline: 0; resize: none;
  color: var(--text); font-size: 14px; font-family: inherit; min-height: 44px; max-height: 160px; padding: 10px 4px;
}
.chat-input textarea:-webkit-autofill { -webkit-text-fill-color: var(--text); -webkit-box-shadow: 0 0 0 1000px var(--input-bg) inset; }
</style>
