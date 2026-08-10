<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">知识库</div>
        <div class="page-sub">管理团队知识库与挂载。仅展示你有权限（创建者 / 可挂载）的库。</div>
      </div>
      <button class="btn-grad" @click="wizard = true">+ 新建知识库</button>
    </div>

    <div class="glass kb-list-wrap" @scroll="onScroll">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>状态</th>
            <th>可挂载</th>
            <th>切片策略</th>
            <th>Embedding</th>
            <th>创建者</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="kb in items" :key="kb.id" class="kb-row" @click="open(kb)">
            <td class="muted">{{ kb.id }}</td>
            <td>
              <div style="font-weight:600">{{ kb.name }}</div>
              <div class="muted" style="font-size:12px;max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ kb.description || '—' }}</div>
            </td>
            <td><span class="tag" :class="kb.status === 'ARCHIVED' ? 'tag-danger' : 'tag-a'">{{ kb.status === 'ARCHIVED' ? '已归档' : '启用' }}</span></td>
            <td>{{ kb.isPublic ? '是' : '否' }}</td>
            <td><span class="tag tag-b">{{ kb.chunkStrategy }}</span></td>
            <td class="muted">{{ kb.embeddingModel || '默认' }}</td>
            <td class="muted">{{ kb.creatorId || '—' }}</td>
            <td class="muted">{{ fmt(kb.createdAt) }}</td>
            <td>
              <button class="btn-del" @click.stop="askDelete(kb)">删除</button>
            </td>
          </tr>
          <tr v-if="!items.length && !loading">
            <td colspan="9" class="muted" style="text-align:center;padding:30px">暂无知识库，点击右上角新建。</td>
          </tr>
        </tbody>
      </table>

      <div v-if="loading" class="kb-more muted">加载中…</div>
      <div v-else-if="hasMore" class="kb-more muted">向下滚动加载更多</div>
      <div v-else-if="items.length" class="kb-more muted">已到底部</div>
    </div>

    <p v-if="error" class="error-text" style="margin-top:14px">{{ error }}</p>

    <CreateKbWizard v-model="wizard" @created="onCreated" />

    <!-- 删除确认弹窗：被 Agent 挂载时明确列出挂载方，不直接卸载 -->
    <DeleteKbModal :visible="delVisible" :kb="delKb" @update:visible="delVisible = $event" @deleted="onDeleted" />
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { listBases } from '../api/knowledge'
import CreateKbWizard from '../components/CreateKbWizard.vue'
import DeleteKbModal from '../components/DeleteKbModal.vue'

const router = useRouter()
const items = ref([])
const nextCursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
const error = ref('')
const wizard = ref(false)
const delVisible = ref(false)
const delKb = ref(null)

async function loadMore() {
  if (loading.value || !hasMore.value) return
  loading.value = true
  error.value = ''
  try {
    const params = nextCursor.value ? { lastId: nextCursor.value, limit: 20 } : { limit: 20 }
    const page = await listBases(params)
    items.value.push(...(page.items || []))
    hasMore.value = page.hasMore
    nextCursor.value = page.nextCursor || null
    await nextTick()
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function onScroll(e) {
  const el = e.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) loadMore()
}

function open(kb) {
  router.push({ name: 'knowledge-detail', params: { kbId: String(kb.id) }, state: { kb } })
}

// 列表删除入口：打开确认弹窗（被 Agent 挂载时由弹窗列出挂载方，不直接卸载）。
function askDelete(kb) {
  delKb.value = kb
  delVisible.value = true
}

// 弹窗内删除成功回调：从列表移除该行
function onDeleted(id) {
  items.value = items.value.filter((x) => x.id !== id)
}

function onCreated() {
  items.value = []
  nextCursor.value = null
  hasMore.value = true
  loadMore()
}

function fmt(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

onMounted(loadMore)
</script>

<style scoped>
.kb-list-wrap { max-height: calc(100vh - 220px); overflow: auto; padding: 6px 4px; }
.kb-row { cursor: pointer; }
.kb-row:hover td { background: var(--glass-strong); }
.btn-del {
  background: transparent; border: 1px solid #e5484d; color: #e5484d;
  border-radius: 8px; padding: 4px 12px; font-size: 13px; cursor: pointer;
}
.btn-del:hover { background: #e5484d; color: #fff; }
.btn-del:disabled { opacity: .5; cursor: not-allowed; }
.kb-more { text-align: center; padding: 16px; font-size: 12px; }
</style>
