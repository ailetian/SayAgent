<template>
  <div class="page detail">
    <div class="page-head">
      <div>
        <div class="page-title">{{ kb ? kb.name : ('知识库 #' + kbId) }}</div>
        <div class="page-sub">{{ (kb && kb.description) || '查看文档、问答、设置、体检与试问台。' }}</div>
      </div>
      <button class="btn-ghost" @click="back">返回列表</button>
    </div>

    <el-tabs v-model="tab" class="kb-tabs">
      <!-- 文档 -->
      <el-tab-pane label="文档" name="docs">
        <UploadPanel :kb-id="kbId" @uploaded="onUploaded" />
        <DocumentList ref="docListRef" :kb-id="kbId" />
      </el-tab-pane>

      <!-- 问答 -->
      <el-tab-pane label="问答" name="qa">
        <div class="field qa-input"><input v-model="q" placeholder="向知识库提问……" @keyup.enter="ask" /></div>
        <div style="margin-top:12px">
          <button class="btn-grad" :disabled="!q || asking" @click="ask">{{ asking ? '思考中…' : '提问' }}</button>
        </div>

        <div v-if="answer" class="qa-out">
          <div v-if="resp.refused" class="refusal">
            <span class="tag tag-danger">已拒答</span>
            <span class="muted" style="font-size:12px">分型：{{ resp.refusalReason }}</span>
          </div>
          <div class="md qa-answer" v-html="rendered" />
          <div v-if="resp.sources && resp.sources.length" class="qa-sources">
            <div class="muted" style="font-size:12px;margin-bottom:6px">来源（{{ resp.sources.length }}）</div>
            <button v-for="s in resp.sources" :key="s.index" class="src-chip" @click="copySrc(s)">
              [{{ s.index }}] {{ s.title || s.documentId }} · seq {{ s.seq }}
            </button>
          </div>
          <div class="muted" style="font-size:12px;margin-top:8px">
            topScore {{ fmt(resp.topScore) }} / 阈值 {{ fmt(resp.threshold) }}
          </div>
        </div>
        <p v-if="qaError" class="error-text" style="margin-top:12px">{{ qaError }}</p>
      </el-tab-pane>

      <!-- 设置 -->
      <el-tab-pane label="设置" name="settings">
        <div v-if="kb" class="glass settings">
          <div class="form-grid">
            <div class="span-2"><label class="form-label">名称</label><div class="field"><input v-model="edit.name" /></div></div>
            <div class="span-2"><label class="form-label">描述</label><div class="field"><input v-model="edit.description" /></div></div>
            <div><label class="form-label">相似度阈值</label><div class="field"><input v-model.number="edit.similarityThreshold" type="number" step="0.05" min="0" max="1" /></div></div>
            <div><label class="form-label">切片策略</label>
              <el-select v-model="edit.chunkStrategy" style="width:100%">
                <el-option label="AUTO" value="AUTO" />
                <el-option label="RECURSIVE" value="RECURSIVE" />
                <el-option label="MARKDOWN_HEADER" value="MARKDOWN_HEADER" />
              </el-select>
            </div>
            <div><label class="form-label">语言</label><div class="field"><input v-model="edit.language" /></div></div>
            <div><label class="form-label">可被挂载</label><el-switch v-model="edit.isPublic" /></div>
          </div>
          <div style="margin-top:16px">
            <button class="btn-grad" :disabled="saving" @click="saveSettings">{{ saving ? '保存中…' : '保存' }}</button>
            <button class="btn-ghost" style="margin-left:8px" :disabled="saving" @click="archive">删除知识库</button>
          </div>
          <div v-if="settingsNote" class="settings-note">{{ settingsNote }}</div>
          <p v-if="settingsError" class="error-text" style="margin-top:12px">{{ settingsError }}</p>
        </div>
        <div v-else class="muted">
          无法读取知识库详情（后端未提供单库查询接口）。问答 / 体检 / 试问台仍可用。
        </div>
      </el-tab-pane>

      <!-- 体检 -->
      <el-tab-pane label="体检" name="health">
        <HealthBoard :kb-id="kbId" />
      </el-tab-pane>

      <!-- 试问台 -->
      <el-tab-pane label="试问台" name="probe">
        <ProbeConsole :kb-id="kbId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { askKb, listBases, updateBase, deleteBase } from '../api/knowledge'
import UploadPanel from '../components/UploadPanel.vue'
import DocumentList from '../components/DocumentList.vue'
import HealthBoard from '../components/HealthBoard.vue'
import ProbeConsole from '../components/ProbeConsole.vue'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => Number(route.params.kbId))
const kb = ref((history.state && history.state.kb) || null)
const tab = ref('docs')
const docs = ref([])
const docListRef = ref(null)

const q = ref('')
const asking = ref(false)
const resp = ref(null)
const answer = ref('')
const rendered = ref('')
const qaError = ref('')

const edit = reactive({ name: '', description: '', similarityThreshold: null, chunkStrategy: 'AUTO', language: 'zh-CN', isPublic: true })
const settingsNote = ref('')
const settingsError = ref('')
const saving = ref(false)

const md = new MarkdownIt()

function back() { router.push('/knowledge') }
// 上传完成后刷新文档列表（K11 GET /{kbId}/documents）——列表里带 jobId，进度与重试由列表组件接管
function onUploaded(list) {
  docs.value = list
  if (docListRef.value) docListRef.value.reload()
}
function fmt(v) { return typeof v === 'number' ? v.toFixed(3) : (v ?? '—') }

function copySrc(s) {
  const text = `doc ${s.documentId} · seq ${s.seq}`
  if (navigator.clipboard) navigator.clipboard.writeText(text)
}

async function ask() {
  if (!q.value) return
  asking.value = true
  qaError.value = ''
  try {
    const r = await askKb(kbId.value, { query: q.value, history: [] })
    resp.value = r
    answer.value = r.answer || ''
    rendered.value = md.render(r.answer || '')
  } catch (e) {
    qaError.value = e.message || '提问失败'
  } finally {
    asking.value = false
  }
}

// 保存设置（K11 PUT /api/knowledge/bases/{id}）：后端只覆盖非空字段，所以空串按「不改」处理
async function saveSettings() {
  saving.value = true
  settingsError.value = ''
  settingsNote.value = ''
  try {
    const payload = {
      name: edit.name || null,
      description: edit.description || null,
      similarityThreshold: edit.similarityThreshold ?? null,
      chunkStrategy: edit.chunkStrategy || null,
      language: edit.language || null,
      isPublic: edit.isPublic
    }
    const vo = await updateBase(kbId.value, payload)
    kb.value = vo
    settingsNote.value = '已保存。阈值 / 切片策略的改动对后续新上传的文档生效；存量文档需重新上传才会按新策略切片。'
  } catch (e) {
    settingsError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

// 删除知识库（K11 DELETE /api/knowledge/bases/{id}）：软删库 + 级联软删文档 + 清 PG 切片
async function archive() {
  if (!window.confirm(`确认删除知识库「${(kb.value && kb.value.name) || kbId.value}」？其下文档将一并软删，且不再被任何 Agent 召回。`)) return
  saving.value = true
  settingsError.value = ''
  settingsNote.value = ''
  try {
    await deleteBase(kbId.value)
    router.push('/knowledge')
  } catch (e) {
    settingsError.value = e.message || '删除失败'
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!kb.value) {
    // 直接访问 /knowledge/:kbId（刷新）时 router state 已丢失，best-effort 从列表取详情
    try {
      const page = await listBases({ limit: 100 })
      const found = (page.items || []).find((x) => x.id === kbId.value)
      if (found) kb.value = found
    } catch (e) { /* 就地提示由组件内错误通道处理；此处静默回退到只读态 */ }
  }
  if (kb.value) {
    edit.name = kb.value.name
    edit.description = kb.value.description || ''
    edit.similarityThreshold = kb.value.similarityThreshold ?? null
    edit.chunkStrategy = kb.value.chunkStrategy || 'AUTO'
    edit.language = kb.value.language || 'zh-CN'
    edit.isPublic = kb.value.isPublic !== false
  }
})
</script>

<style scoped>
.detail { height: 100%; overflow: auto; }
.docs-note { margin-top: 14px; }
.qa-input { max-width: 720px; }
.qa-out { margin-top: 16px; }
.refusal { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.qa-answer { padding: 14px 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--glass); }
.qa-sources { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; }
.src-chip {
  background: var(--glass); border: 1px solid var(--line); color: var(--accent-a);
  border-radius: 999px; padding: 4px 12px; font-size: 12px; cursor: pointer;
}
.src-chip:hover { border-color: var(--accent-a); }
.settings { padding: 20px 22px; max-width: 720px; }
.settings-note {
  margin-top: 14px; padding: 12px 14px; border: 1px dashed var(--line); border-radius: 10px;
  font-size: 12px; color: var(--muted); white-space: pre-wrap; word-break: break-all;
}
</style>
