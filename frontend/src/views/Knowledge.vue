<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">知识库</div>
        <div class="page-sub">
          上传文档到指定知识库并检索。当前后端仅提供 upload / retrieve（无「列出已上传文档」端点）。
        </div>
      </div>
    </div>

    <div class="kg-grid">
      <!-- 上传 -->
      <section class="glass kg-card">
        <h3 class="kg-h">上传文档</h3>
        <label class="form-label">知识库 ID (kbId)</label>
        <div class="field"><input v-model.number="kbId" type="number" placeholder="目标知识库 ID" /></div>
        <label class="form-label" style="margin-top:14px">文件名</label>
        <div class="field"><input v-model="filename" placeholder="policy-2026.txt" /></div>
        <label class="form-label" style="margin-top:14px">文档内容</label>
        <div class="field"><textarea v-model="content" placeholder="粘贴文档正文……" style="min-height:150px" /></div>
        <div style="margin-top:16px">
          <button class="btn-grad" :disabled="uploading" @click="upload">上传并建索引</button>
        </div>
        <div v-if="lastDoc" class="kg-result">
          <div class="muted" style="font-size:12px;margin-bottom:6px">上次上传结果</div>
          <div>状态：<span class="tag" :class="docStatusClass(lastDoc.status)">{{ lastDoc.status }}</span></div>
          <div class="muted" style="font-size:12px;margin-top:4px">
            文档 #{{ lastDoc.id }} · 分块 {{ lastDoc.chunkCount ?? '—' }} · {{ lastDoc.filename }}
          </div>
        </div>
      </section>

      <!-- 检索 -->
      <section class="glass kg-card">
        <h3 class="kg-h">检索</h3>
        <label class="form-label">查询</label>
        <div class="field"><input v-model="query" placeholder="如：年假怎么算？" @keyup.enter="search" /></div>
        <div class="kg-row">
          <div style="flex:1">
            <label class="form-label" style="margin-top:14px">知识库 ID</label>
            <div class="field"><input v-model.number="retKbId" type="number" placeholder="同上" /></div>
          </div>
          <div style="width:120px">
            <label class="form-label" style="margin-top:14px">topK</label>
            <div class="field"><input v-model.number="topK" type="number" min="1" max="20" /></div>
          </div>
        </div>
        <div style="margin-top:16px">
          <button class="btn-grad" :disabled="searching" @click="search">检索</button>
        </div>
        <div v-if="chunks.length" class="kg-chunks">
          <div v-for="(c, i) in chunks" :key="i" class="kg-chunk">
            <div class="kg-chunk-head">
              <span class="tag tag-b">score {{ c.score != null ? c.score.toFixed(3) : '—' }}</span>
              <span v-if="c.source" class="muted" style="font-size:12px">{{ c.source }}</span>
            </div>
            <div class="md">{{ c.content }}</div>
          </div>
        </div>
        <div v-else-if="searched" class="muted" style="margin-top:14px">无命中结果</div>
      </section>
    </div>

    <p v-if="errorMsg" class="error-text" style="margin-top:16px">{{ errorMsg }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadDoc, retrieve } from '../api/knowledge'

const kbId = ref(null)
const filename = ref('')
const content = ref('')
const uploading = ref(false)
const lastDoc = ref(null)

const query = ref('')
const retKbId = ref(null)
const topK = ref(4)
const searching = ref(false)
const chunks = ref([])
const searched = ref(false)

const errorMsg = ref('')

function docStatusClass(s) {
  if (s === 'INDEXED') return 'tag-a'
  if (s === 'FAILED') return 'tag-danger'
  return ''
}

async function upload() {
  errorMsg.value = ''
  if (kbId.value == null || !filename.value || !content.value) {
    errorMsg.value = '请填写知识库 ID、文件名与内容'
    return
  }
  uploading.value = true
  try {
    const doc = await uploadDoc({
      kbId: Number(kbId.value),
      filename: filename.value,
      content: content.value
    })
    lastDoc.value = doc
    ElMessage.success('已上传，索引状态见下方')
  } catch (e) {
    errorMsg.value = e.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

async function search() {
  errorMsg.value = ''
  const useKb = retKbId.value != null ? retKbId.value : kbId.value
  if (!query.value || useKb == null) {
    errorMsg.value = '请填写查询与知识库 ID'
    return
  }
  searching.value = true
  chunks.value = []
  searched.value = false
  try {
    const res = await retrieve({ query: query.value, kbId: Number(useKb), topK: Number(topK.value || 4) })
    chunks.value = Array.isArray(res) ? res : []
    searched.value = true
  } catch (e) {
    errorMsg.value = e.message || '检索失败'
  } finally {
    searching.value = false
  }
}
</script>

<style scoped>
.kg-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.kg-card { padding: 20px 22px; }
.kg-h { margin: 0 0 16px; font-size: 15px; font-weight: 700; }
.kg-row { display: flex; gap: 14px; }
.kg-result { margin-top: 16px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--glass); }
.kg-chunks { margin-top: 16px; display: flex; flex-direction: column; gap: 12px; max-height: 360px; overflow: auto; }
.kg-chunk { padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--glass); }
.kg-chunk-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
@media (max-width: 900px) { .kg-grid { grid-template-columns: 1fr; } }
</style>
