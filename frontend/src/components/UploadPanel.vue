<template>
  <div class="upload-panel">
    <div class="up-head">
      <div class="up-title">上传文档到知识库 #{{ kbId }}</div>
      <div class="muted" style="font-size:12px">
        单次最多 10 个；支持 .txt / .md / .pdf / .docx。PDF、DOCX 由后端 Tika 解析，前端原样发送文件字节。
      </div>
    </div>

    <!-- 添加文本 -->
    <div class="up-add glass">
      <label class="form-label">粘贴文本文档</label>
      <div class="field"><input v-model="newName" placeholder="文档名（如：年假政策.txt）" /></div>
      <div class="field" style="margin-top:10px">
        <textarea v-model="newContent" placeholder="粘贴文档正文……" style="min-height:90px" />
      </div>
      <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap">
        <button class="btn-ghost" :disabled="drafts.length >= 10" @click="addText">+ 添加文本文档</button>
        <label class="btn-ghost" style="cursor:pointer;margin:0">
          + 选择 .txt / .md / .pdf / .docx 文件
          <input type="file" accept=".txt,.md,.pdf,.docx" multiple style="display:none" @change="onFiles" />
        </label>
      </div>
    </div>

    <!-- 草稿列表 -->
    <div v-if="drafts.length" class="up-drafts">
      <div v-for="(d, i) in drafts" :key="i" class="up-draft">
        <span class="tag" :class="d.type === 'FILE' ? 'tag-b' : ''">{{ d.type }}</span>
        <span style="font-weight:600">{{ d.filename || d.title || '未命名' }}</span>
        <span class="muted" style="font-size:12px">{{ d.file ? formatSize(d.file.size) : ((d.content || '').length + ' 字') }}</span>
        <button class="link-danger" @click="removeDraft(i)">移除</button>
      </div>
    </div>

    <div style="margin-top:12px">
      <button class="btn-grad" :disabled="!drafts.length || uploading" @click="startUpload">
        {{ uploading ? '上传中…' : `上传 ${drafts.length} 个文档` }}
      </button>
      <span class="muted" style="font-size:12px;margin-left:10px">{{ drafts.length }}/10</span>
    </div>

    <!-- 已上传节点 -->
    <div v-if="uploaded.length" class="up-nodes">
      <div v-for="(n, i) in uploaded" :key="i" class="up-node">
        <span class="tag" :class="statusClass(n.status)">{{ statusLabel(n.status) }}</span>
        <span style="font-weight:600">{{ n.filename || n.title || n.docId }}</span>
        <span class="muted" style="font-size:12px">docId {{ n.docId }}</span>
        <button v-if="n.status !== 'INDEXED'" class="link-a" :disabled="uploading" @click="retry(n)">重试</button>
        <span v-if="n.status === 'FAILED' && n.error" class="error-text" style="font-size:12px">{{ n.error }}</span>
      </div>
      <div class="muted up-note">
        此处为上传瞬间的状态快照。实时「逐节点进度」与失败重试请看下方文档列表——
        列表随每篇文档返回最近一条索引任务（K11），会自动轮询阶段与进度。
      </div>
    </div>

    <p v-if="error" class="error-text" style="margin-top:12px">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { uploadBatch, uploadFiles } from '../api/knowledge'

const props = defineProps({ kbId: { type: [Number, String], required: true } })
const emit = defineEmits(['uploaded'])

const drafts = ref([])
const uploaded = ref([])
const newName = ref('')
const newContent = ref('')
const uploading = ref(false)
const error = ref('')

function addText() {
  if (!newContent.value.trim()) { error.value = '请填写文本内容'; return }
  if (drafts.value.length >= 10) { error.value = '单次最多 10 个'; return }
  error.value = ''
  drafts.value.push({
    type: 'TEXT',
    title: newName.value || '未命名文档',
    filename: newName.value || null,
    content: newContent.value
  })
  newName.value = ''
  newContent.value = ''
}

function onFiles(e) {
  const files = Array.from(e.target.files || [])
  for (const f of files) {
    if (drafts.value.length >= 10) { error.value = '单次最多 10 个'; break }
    // 存原始 File 对象，上传时以二进制 multipart 发送（PDF/DOCX 不能当文本读）
    drafts.value.push({ type: 'FILE', title: f.name, filename: f.name, file: f })
  }
  e.target.value = ''
}

function removeDraft(i) { drafts.value.splice(i, 1) }

function buildNode(it, draft) {
  return {
    docId: it.docId,
    status: it.status,
    filename: draft.filename,
    title: draft.title,
    content: draft.content,
    type: draft.type,
    file: draft.file,
    error: null
  }
}

async function startUpload() {
  if (!drafts.value.length) return
  if (drafts.value.length > 10) { error.value = '单次最多 10 个'; return }
  uploading.value = true
  error.value = ''
  const textDrafts = drafts.value.filter((d) => d.type !== 'FILE')
  const fileDrafts = drafts.value.filter((d) => d.type === 'FILE')
  try {
    const nodes = []
    if (textDrafts.length) {
      const items = textDrafts.map((d) => ({ type: d.type, filename: d.filename, title: d.title, content: d.content }))
      const resp = await uploadBatch(props.kbId, items)
      ;(resp.items || []).forEach((it, i) => nodes.push(buildNode(it, textDrafts[i])))
    }
    if (fileDrafts.length) {
      const files = fileDrafts.map((d) => d.file)
      const resp = await uploadFiles(props.kbId, files)
      ;(resp.items || []).forEach((it, i) => nodes.push(buildNode(it, fileDrafts[i])))
    }
    uploaded.value.push(...nodes)
    drafts.value = []
    emit('uploaded', uploaded.value)
  } catch (e) {
    error.value = e.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

async function retry(node) {
  uploading.value = true
  error.value = ''
  try {
    let it
    if (node.file) {
      const resp = await uploadFiles(props.kbId, [node.file])
      it = resp.items && resp.items[0]
    } else {
      const resp = await uploadBatch(props.kbId, [
        { type: node.type, filename: node.filename, title: node.title, content: node.content }
      ])
      it = resp.items && resp.items[0]
    }
    if (it) { node.docId = it.docId; node.status = it.status }
  } catch (e) {
    error.value = e.message || '重试失败'
  } finally {
    uploading.value = false
  }
}

function formatSize(bytes) {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function statusClass(s) {
  if (s === 'INDEXED') return 'tag-a'
  if (s === 'FAILED') return 'tag-danger'
  return 'tag-b'
}
function statusLabel(s) {
  return { UPLOADED: '已上传', INDEXING: '索引中', INDEXED: '已索引', FAILED: '失败' }[s] || s
}
</script>

<style scoped>
.up-head { margin-bottom: 14px; }
.up-title { font-size: 15px; font-weight: 700; margin-bottom: 4px; }
.up-add { padding: 14px 16px; }
.up-drafts { margin-top: 14px; display: flex; flex-direction: column; gap: 8px; }
.up-draft { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--glass); }
.up-node { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--glass); }
.up-nodes { margin-top: 16px; display: flex; flex-direction: column; gap: 8px; }
.up-note { font-size: 12px; padding: 8px 4px; }
</style>
