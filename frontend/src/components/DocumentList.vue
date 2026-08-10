<template>
  <div class="doc-list">
    <div class="dl-head">
      <div class="dl-title">文档列表</div>
      <div class="dl-actions">
        <button class="btn-ghost" :disabled="loading" @click="reload">{{ loading ? '加载中…' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="!docs.length && !loading" class="muted dl-empty">
      该知识库暂无文档。用上方面板上传后点「刷新」。
    </div>

    <div v-for="d in docs" :key="d.docId" class="dl-item">
      <div class="dl-row">
        <span class="tag" :class="statusClass(d.status)">{{ statusLabel(d.status) }}</span>
        <span class="dl-name">{{ d.title || d.docId }}</span>
        <span class="muted dl-meta">{{ d.chunkCount }} 切片 · {{ fmtSize(d.sizeBytes) }} · {{ fmtTime(d.updatedAt) }}</span>
        <span class="dl-spacer" />
        <button class="link-a" :disabled="busyId === d.docId" @click="viewSource(d)">查看源文档</button>
        <button class="link-a" :disabled="busyId === d.docId" @click="toggleChunks(d)">切片预览</button>
        <button class="link-a" :disabled="busyId === d.docId" @click="openReupload(d)">重新上传</button>
        <button class="link-danger" :disabled="busyId === d.docId" @click="removeDoc(d)">删除</button>
      </div>
      <div class="muted dl-id">docId {{ d.docId }}</div>

      <!-- 切片预览：直接看该文档被切成了哪几段（按 seq） -->
      <div v-if="previewId === d.docId" class="dl-chunks glass">
        <div class="dl-chunks-head">
          <span class="muted">切片预览（共 {{ chunks.length }} 段，按入库顺序）</span>
          <button class="link-a" @click="previewId = ''">收起</button>
        </div>
        <div v-if="chunksLoading" class="muted dl-chunks-empty">加载切片中…</div>
        <div v-else-if="!chunks.length" class="muted dl-chunks-empty">该文档暂无切片（可能尚未索引成功或解析后无正文）。</div>
        <div v-for="(c, i) in chunks" :key="i" class="dl-chunk">
          <div class="dl-chunk-meta"><span class="tag tag-b">#{{ c.chunkIndex }}</span><span class="muted">长度 {{ (c.content || '').length }}</span></div>
          <pre class="dl-chunk-text">{{ c.content }}</pre>
        </div>
      </div>

      <!-- 重新上传：复用同一 documentId，后端撕旧切片贴新切片 -->
      <div v-if="reuploadId === d.docId" class="dl-reupload glass">
        <label class="form-label">新内容（覆盖本篇文档，docId 不变）</label>
        <div class="field">
          <input v-model="ruName" placeholder="文档名（留空沿用原标题）" />
        </div>
        <div class="field" style="margin-top:10px">
          <textarea v-model="ruContent" placeholder="粘贴新正文……" style="min-height:90px" />
        </div>
        <div class="dl-ru-btns">
          <label class="btn-ghost" style="cursor:pointer;margin:0">
            选择 .txt / .md 文件
            <input type="file" accept=".txt,.md" style="display:none" @change="onFile" />
          </label>
          <button class="btn-grad" :disabled="!ruContent || busyId === d.docId" @click="submitReupload(d)">
            {{ busyId === d.docId ? '提交中…' : '确认重新上传' }}
          </button>
          <button class="btn-ghost" @click="closeReupload">取消</button>
        </div>
      </div>

      <!-- 索引进度：jobId 由文档列表随摘要返回（K11），据此查状态 / 重试 -->
      <div v-if="jobs[d.docId]" class="dl-job">
        <span class="tag" :class="jobClass(jobs[d.docId].status)">{{ jobs[d.docId].status }}</span>
        <span class="muted dl-meta">
          阶段 {{ jobs[d.docId].stage }} · 进度 {{ jobs[d.docId].progress || '—' }} · 重试 {{ jobs[d.docId].retryCount }}
        </span>
        <span v-if="jobs[d.docId].errorMessage" class="error-text dl-meta">
          {{ jobs[d.docId].failStage }}：{{ jobs[d.docId].errorMessage }}
        </span>
        <button
          v-if="jobs[d.docId].status === 'FAILED'"
          class="link-a"
          :disabled="busyId === d.docId"
          @click="retryJob(d)"
        >重试索引</button>
      </div>
      <div v-else-if="d.status === 'FAILED' && d.jobId" class="dl-job">
        <button class="link-a" :disabled="busyId === d.docId" @click="retryJob(d)">重试索引</button>
      </div>
    </div>

    <div v-if="hasMore" class="dl-more">
      <button class="btn-ghost" :disabled="loading" @click="loadMore">加载更多</button>
    </div>

    <p v-if="error" class="error-text" style="margin-top:12px">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import {
  listDocuments,
  deleteDocument,
  reuploadDoc,
  getIndexingJob,
  retryIndexingJob,
  getDocumentSource,
  getDocumentChunks
} from '../api/knowledge'

const props = defineProps({ kbId: { type: [Number, String], required: true } })

const docs = ref([])
const nextCursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const busyId = ref('')

// 重新上传表单态
const reuploadId = ref('')
const ruName = ref('')
const ruContent = ref('')

// 切片预览态
const previewId = ref('')
const chunks = ref([])
const chunksLoading = ref(false)

// docId -> IndexingJobVO；轮询定时器
const jobs = ref({})
let timer = null

async function fetchPage(cursor) {
  loading.value = true
  error.value = ''
  try {
    const params = { limit: 20 }
    if (cursor) params.lastId = cursor
    const page = await listDocuments(props.kbId, params)
    const items = page.items || []
    if (cursor) docs.value.push(...items)
    else { docs.value = items; jobs.value = {} }
    nextCursor.value = page.nextCursor
    hasMore.value = !!page.hasMore
    refreshActiveJobs(items)
  } catch (e) {
    error.value = e.message || '加载文档列表失败'
  } finally {
    loading.value = false
  }
}

// 只给「还没跑完 / 失败」的文档拉任务详情：已索引成功的没必要再查一遍，省请求
function refreshActiveJobs(items) {
  items
    .filter((d) => d.jobId && d.status !== 'INDEXED')
    .forEach((d) => pollJob(d.docId, d.jobId))
}

function reload() { fetchPage(null) }
function loadMore() { if (nextCursor.value) fetchPage(nextCursor.value) }

function openReupload(d) {
  reuploadId.value = d.docId
  ruName.value = d.title || ''
  ruContent.value = ''
  error.value = ''
}
function closeReupload() {
  reuploadId.value = ''
  ruName.value = ''
  ruContent.value = ''
}

// 查看源文档：走鉴权请求拿 Blob，PDF 新标签内联预览，其余触发下载
async function viewSource(d) {
  busyId.value = d.docId
  error.value = ''
  try {
    const blob = await getDocumentSource(props.kbId, d.docId)
    const url = URL.createObjectURL(blob)
    const isPdf = (blob.type || '').includes('pdf')
    if (isPdf) {
      window.open(url, '_blank')
    } else {
      const a = document.createElement('a')
      a.href = url
      a.download = (d.title || 'document') + (extFromName(d.title) || '.txt')
      a.click()
    }
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (e) {
    error.value = e.message || '查看源文档失败'
  } finally {
    busyId.value = ''
  }
}

// 切片预览：展开/收起该文档的全部切片
async function toggleChunks(d) {
  if (previewId.value === d.docId) {
    previewId.value = ''
    return
  }
  previewId.value = d.docId
  chunks.value = []
  chunksLoading.value = true
  error.value = ''
  try {
    chunks.value = await getDocumentChunks(props.kbId, d.docId)
  } catch (e) {
    error.value = e.message || '加载切片失败'
  } finally {
    chunksLoading.value = false
  }
}

function extFromName(name) {
  if (!name) return ''
  const dot = name.lastIndexOf('.')
  return dot >= 0 ? name.substring(dot) : ''
}

function onFile(e) {
  const f = (e.target.files || [])[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => {
    ruContent.value = String(reader.result || '')
    if (!ruName.value) ruName.value = f.name
  }
  reader.readAsText(f)
  e.target.value = ''
}

// 重新上传：documentId 透传给单文档上传接口，后端转 beginUpdate（复用文档身份）
async function submitReupload(d) {
  if (!ruContent.value) { error.value = '请填写新内容或选择文件'; return }
  busyId.value = d.docId
  error.value = ''
  try {
    const isFile = /\.(txt|md)$/i.test(ruName.value || '')
    await reuploadDoc(props.kbId, d.docId, {
      type: isFile ? 'FILE' : 'TEXT',
      filename: isFile ? ruName.value : null,
      title: ruName.value || d.title,
      content: ruContent.value
    })
    closeReupload()
    await fetchPage(null)
  } catch (e) {
    error.value = e.message || '重新上传失败'
  } finally {
    busyId.value = ''
  }
}

async function removeDoc(d) {
  if (!window.confirm(`确认删除文档「${d.title || d.docId}」？删除后其切片将不再被召回。`)) return
  busyId.value = d.docId
  error.value = ''
  try {
    await deleteDocument(props.kbId, d.docId)
    docs.value = docs.value.filter((x) => x.docId !== d.docId)
    delete jobs.value[d.docId]
  } catch (e) {
    error.value = e.message || '删除失败'
  } finally {
    busyId.value = ''
  }
}

// 重试索引：jobId 来自文档摘要（K11 DocumentSummaryVO.jobId），从失败节点续跑
async function retryJob(d) {
  if (!d.jobId) { error.value = '该文档没有可重试的索引任务'; return }
  busyId.value = d.docId
  error.value = ''
  try {
    await retryIndexingJob(props.kbId, d.jobId)
    await pollJob(d.docId, d.jobId)
  } catch (e) {
    error.value = e.message || '重试失败'
  } finally {
    busyId.value = ''
  }
}

async function pollJob(docId, jobId) {
  try {
    const vo = await getIndexingJob(props.kbId, jobId)
    jobs.value = { ...jobs.value, [docId]: vo }
  } catch (e) { /* 轮询失败静默，避免刷屏；错误由用户手动刷新暴露 */ }
}

function startPolling() {
  timer = window.setInterval(() => {
    const running = Object.entries(jobs.value)
      .filter(([, v]) => v && (v.status === 'QUEUED' || v.status === 'RUNNING'))
    if (!running.length) return
    running.forEach(([docId, v]) => pollJob(docId, v.id))
  }, 3000)
}

defineExpose({ reload })

function statusClass(s) {
  if (s === 'INDEXED') return 'tag-a'
  if (s === 'FAILED') return 'tag-danger'
  return 'tag-b'
}
function statusLabel(s) {
  return { UPLOADED: '已上传', INDEXING: '索引中', INDEXED: '已索引', FAILED: '失败' }[s] || s
}
function jobClass(s) {
  if (s === 'SUCCESS') return 'tag-a'
  if (s === 'FAILED') return 'tag-danger'
  return 'tag-b'
}
function fmtSize(b) {
  if (b == null) return '—'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(1)} MB`
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }

onMounted(() => { fetchPage(null); startPolling() })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.doc-list { margin-top: 18px; }
.dl-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.dl-title { font-size: 15px; font-weight: 700; }
.dl-empty { padding: 14px 4px; font-size: 13px; }
.dl-item {
  padding: 10px 14px; border: 1px solid var(--line); border-radius: 10px;
  background: var(--glass); margin-bottom: 8px;
}
.dl-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dl-name { font-weight: 600; }
.dl-meta { font-size: 12px; }
.dl-id { font-size: 11px; margin-top: 4px; word-break: break-all; }
.dl-spacer { flex: 1; }
.dl-reupload { margin-top: 10px; padding: 12px 14px; }
.dl-ru-btns { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.dl-job { margin-top: 8px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dl-more { margin-top: 10px; }
.dl-chunks { margin-top: 10px; padding: 12px 14px; }
.dl-chunks-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.dl-chunks-empty { padding: 8px 2px; font-size: 13px; }
.dl-chunk { border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; background: var(--glass-strong); }
.dl-chunk-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.dl-chunk-text { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: 'IBM Plex Mono', monospace; font-size: 12px; line-height: 1.5; max-height: 220px; overflow: auto; color: var(--text); }
</style>
