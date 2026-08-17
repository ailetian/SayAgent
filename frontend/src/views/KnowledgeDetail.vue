<template>
  <div class="page detail">
    <div class="page-head">
      <div>
        <div class="page-title">{{ kb ? kb.name : ('知识库 #' + kbId) }}</div>
        <div class="page-sub">{{ (kb && kb.description) || '查看文档、问答、设置、体检与试问台。' }}</div>
      </div>
      <button class="btn-ghost" @click="back">返回列表</button>
    </div>

    <el-tabs v-model="tab" class="kb-tabs" @tab-change="onTab">
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
            <button class="btn-ghost" style="margin-left:8px" :disabled="saving" @click="delVisible = true">删除知识库</button>
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

      <!-- 授权（M9/T7）：管理员 / 创建者可授权角色或具体用户，并查看 / 撤销 -->
      <el-tab-pane label="授权" name="access">
        <div v-if="!canManage" class="muted" style="padding:10px 2px">
          仅管理员或知识库创建者可以管理授权。
        </div>
        <div v-else class="glass access">
          <p v-if="grantError" class="error-text" style="margin-bottom:10px">{{ grantError }}</p>

          <div class="form-label">当前授权</div>
          <div v-if="grants.length === 0" class="muted">暂无授权，仅创建者本人可见。</div>
          <div v-for="g in grants" :key="g.principalType + ':' + g.principalId" class="grant-row">
            <span class="tag" :class="g.principalType === 'ROLE' ? 'tag-role' : 'tag-user'">{{ g.principalType }}</span>
            <span class="grant-id">{{ g.principalId }}</span>
            <span class="muted grant-perms">{{ permText(g) }}</span>
            <button class="btn-ghost btn-sm" :disabled="busy" @click="revokeGrant(g)">撤销</button>
          </div>

          <div class="form-label" style="margin-top:18px">按角色批量授权（可读）</div>
          <div class="muted" style="font-size:12px;margin:2px 0 8px">ADMIN 默认拥有全部权限，无需授权。</div>
          <div class="role-row">
            <label class="role-chk"><input type="checkbox" v-model="roleChecks.OPERATOR" /> OPERATOR</label>
            <label class="role-chk"><input type="checkbox" v-model="roleChecks.USER" /> USER</label>
            <button class="btn-grad btn-sm" :disabled="busy" @click="batchGrantRoles">授权选中角色</button>
          </div>

          <div class="form-label" style="margin-top:18px">添加个人（可读）</div>
          <div class="person-row">
            <input class="field" v-model="newUser" placeholder="输入用户名" @keyup.enter="addUser" />
            <button class="btn-grad btn-sm" :disabled="busy || !newUser" @click="addUser">添加</button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 删除确认弹窗：被 Agent 挂载时明确列出挂载方，不直接卸载 -->
    <DeleteKbModal :visible="delVisible" :kb="kb || { id: kbId }" @update:visible="delVisible = $event" @deleted="onDeleted" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { askKb, listBases, updateBase, listGrants, grantAccess, revokeAccess } from '../api/knowledge'
import { useAuthStore } from '../stores/auth'
import UploadPanel from '../components/UploadPanel.vue'
import DocumentList from '../components/DocumentList.vue'
import HealthBoard from '../components/HealthBoard.vue'
import ProbeConsole from '../components/ProbeConsole.vue'
import DeleteKbModal from '../components/DeleteKbModal.vue'

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
const delVisible = ref(false)

// ===== M9/T7 授权 Tab 状态 =====
const auth = useAuthStore()
const grants = ref([])
const grantError = ref('')
const newUser = ref('')
const roleChecks = reactive({ OPERATOR: false, USER: false })
const busy = ref(false)

// 管理者 = ADMIN 或本库创建者（与后端 requireManager 语义一致：非管理者调接口会被 403）
const canManage = computed(() => {
  const roles = auth.roles || []
  if (roles.includes('ADMIN')) return true
  if (kb.value && kb.value.creatorId && auth.user && auth.user.username) {
    return kb.value.creatorId === auth.user.username
  }
  return false
})

function permText(g) {
  const ps = []
  if (g.canRead) ps.push('读')
  if (g.canWrite) ps.push('写')
  if (g.canUse) ps.push('用')
  if (g.canEdit) ps.push('编')
  return ps.length ? ps.join('/') : '无'
}

async function loadGrants() {
  grantError.value = ''
  try {
    grants.value = (await listGrants('KB', kbId.value)) || []
  } catch (e) {
    grantError.value = e.message || '加载授权失败'
  }
}

async function batchGrantRoles() {
  const selected = Object.keys(roleChecks).filter((r) => roleChecks[r])
  if (selected.length === 0) { grantError.value = '请先勾选至少一个角色'; return }
  busy.value = true; grantError.value = ''
  try {
    for (const r of selected) {
      await grantAccess({ principalType: 'ROLE', principalId: r, resourceType: 'KB', resourceId: kbId.value,
        canRead: true, canWrite: false, canUse: true, canEdit: false })
    }
    await loadGrants()
  } catch (e) {
    grantError.value = e.message || '授权失败'
  } finally {
    busy.value = false
  }
}

async function addUser() {
  if (!newUser.value) return
  busy.value = true; grantError.value = ''
  try {
    await grantAccess({ principalType: 'USER', principalId: newUser.value.trim(), resourceType: 'KB', resourceId: kbId.value,
      canRead: true, canWrite: false, canUse: true, canEdit: false })
    newUser.value = ''
    await loadGrants()
  } catch (e) {
    grantError.value = e.message || '添加失败'
  } finally {
    busy.value = false
  }
}

async function revokeGrant(g) {
  busy.value = true; grantError.value = ''
  try {
    await revokeAccess({ principalType: g.principalType, principalId: g.principalId, resourceType: 'KB', resourceId: kbId.value })
    await loadGrants()
  } catch (e) {
    grantError.value = e.message || '撤销失败'
  } finally {
    busy.value = false
  }
}

// 切到授权 Tab 时刷新列表（仅管理者）
function onTab(name) {
  if (name === 'access' && canManage.value) loadGrants()
}

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

// 删除知识库：交给 DeleteKbModal 处理（被 Agent 挂载时弹窗列出挂载方，不直接卸载）
function onDeleted() {
  router.push('/knowledge')
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
    if (canManage.value) loadGrants()
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
.access { padding: 18px 20px; max-width: 720px; }
.grant-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line);
}
.grant-id { font-weight: 600; color: var(--accent-a); }
.grant-perms { font-size: 12px; }
.role-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.role-chk { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text); }
.person-row { display: flex; gap: 10px; max-width: 420px; }
.btn-sm { padding: 6px 14px; font-size: 13px; }
.tag-role { background: rgba(124, 92, 255, 0.16); color: #9d86ff; border-color: rgba(124, 92, 255, 0.4); }
.tag-user { background: rgba(45, 212, 191, 0.16); color: #2dd4bf; border-color: rgba(45, 212, 191, 0.4); }
</style>
