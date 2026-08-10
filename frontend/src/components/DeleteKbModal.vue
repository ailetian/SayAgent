<template>
  <transition name="fade">
    <div v-if="visible" class="modal-mask" @click.self="close">
      <div class="modal">
        <div class="modal-title">
          <span v-if="!blocked && !genericError">确认删除知识库</span>
          <span v-else-if="blocked">无法删除：知识库被 Agent 占用</span>
          <span v-else>删除失败</span>
        </div>

        <!-- 未拦截：正常二次确认 -->
        <div v-if="!blocked && !genericError" class="modal-body">
          <p>即将删除知识库「<b>{{ kb && kb.name ? kb.name : ('#' + (kb && kb.id)) }}</b>」，其下所有文档将一并软删，且不再被任何 Agent 召回。</p>
          <p class="muted small">若该库正被某个 Agent 挂载，将无法直接删除，需先到对应 Agent 卸载。</p>
        </div>

        <!-- 挂载拦截：明确列出当前挂载方 -->
        <div v-else-if="blocked" class="modal-body">
          <p>知识库「<b>{{ kb && kb.name ? kb.name : ('#' + (kb && kb.id)) }}</b>」<b>当前被以下 Agent 挂载，无法删除</b>。请先到对应 Agent 将其卸载后，再回来删除。</p>
          <ul v-if="agents.length" class="agent-list">
            <li v-for="a in agents" :key="a">{{ a }}</li>
          </ul>
          <p v-else class="muted small">{{ rawMessage }}</p>
        </div>

        <!-- 其它错误 -->
        <div v-else class="modal-body">
          <p class="err">{{ rawMessage }}</p>
        </div>

        <div class="modal-actions">
          <button class="btn-ghost" @click="close">{{ blocked || genericError ? '我知道了' : '取消' }}</button>
          <button v-if="!blocked && !genericError" class="btn-del-solid" :disabled="busy" @click="confirm">{{ busy ? '删除中…' : '确认删除' }}</button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref } from 'vue'
import { deleteBase } from '../api/knowledge'

const props = defineProps({ visible: Boolean, kb: Object })
const emit = defineEmits(['update:visible', 'deleted'])

const busy = ref(false)
const blocked = ref(false)
const genericError = ref(false)
const rawMessage = ref('')
const agents = ref([])

function close() {
  emit('update:visible', false)
  // 延迟重置，避免关闭动画期间闪现旧状态
  setTimeout(() => {
    blocked.value = false
    genericError.value = false
    rawMessage.value = ''
    agents.value = []
    busy.value = false
  }, 200)
}

// 从后端 1005 文案里解析出挂载的 Agent 名称清单。
// 文案形如：「知识库已被 Agent 挂载，请先到对应 Agent 卸载后再删除：「库名」当前挂载方：验收客服Agent、xxx」
// 用 lastIndexOf 取最后一个「当前挂载方」之后部分（兼容历史重复前缀），按中文顿号/逗号切分。
function parseAgents(msg) {
  const marker = '当前挂载方'
  const i = msg.lastIndexOf(marker)
  if (i < 0) return null
  let rest = msg.slice(i + marker.length).replace(/^[:：]\s*/, '').trim()
  if (!rest) return []
  // 指向已软删 Agent 的残留链接：没有具体名称，直接以原文提示
  if (rest.includes('残留链接') || rest.includes('已不存在')) { agents.value = []; return [] }
  return rest.split(/[、,，]/).map(s => s.trim()).filter(Boolean)
}

async function confirm() {
  if (!props.kb) return
  busy.value = true
  blocked.value = false
  genericError.value = false
  try {
    await deleteBase(props.kb.id)
    emit('deleted', props.kb.id)
    close()
  } catch (e) {
    const msg = (e && e.message) || '删除失败'
    rawMessage.value = msg
    const parsed = parseAgents(msg)
    if (parsed !== null) {
      blocked.value = true
      agents.value = parsed
    } else if (msg.includes('Agent 挂载') || msg.includes('请先')) {
      blocked.value = true
      agents.value = []
    } else {
      genericError.value = true
    }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.modal-mask {
  position: fixed; inset: 0; background: rgba(8, 12, 22, 0.55);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
  backdrop-filter: blur(2px);
}
.modal {
  width: 460px; max-width: calc(100vw - 40px);
  background: var(--glass, #fff); border: 1px solid var(--line, #e3e8ef);
  border-radius: 16px; padding: 22px 24px; color: var(--text, #1a2233);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.28);
}
.modal-title { font-size: 17px; font-weight: 700; margin-bottom: 14px; }
.modal-body { font-size: 14px; line-height: 1.7; }
.modal-body .muted { color: var(--muted, #6b7280); }
.modal-body .small { font-size: 12.5px; margin-top: 8px; }
.modal-body .err { color: #e5484d; }
.agent-list {
  margin: 12px 0 0; padding: 10px 14px; list-style: none;
  background: rgba(229, 72, 77, 0.08); border: 1px solid rgba(229, 72, 77, 0.25);
  border-radius: 10px;
}
.agent-list li { padding: 3px 0; color: #c0392b; font-weight: 600; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-del-solid {
  background: #e5484d; color: #fff; border: none; border-radius: 9px;
  padding: 8px 18px; font-size: 14px; cursor: pointer; font-weight: 600;
}
.btn-del-solid:hover { background: #cf3b40; }
.btn-del-solid:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-ghost {
  background: transparent; border: 1px solid var(--line, #d0d7e2); color: var(--muted, #6b7280);
  border-radius: 9px; padding: 8px 18px; font-size: 14px; cursor: pointer;
}
.btn-ghost:hover { background: var(--glass-strong, #f3f5f9); }
.fade-enter-active, .fade-leave-active { transition: opacity 0.18s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
