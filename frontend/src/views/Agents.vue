<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">Agent 配置</div>
        <div class="page-sub">定义 AI 员工：绑定模型、系统提示词、知识库与工具引用。</div>
      </div>
      <button class="btn-grad" @click="openCreate">+ 新增 Agent</button>
    </div>

    <div class="glass" style="padding: 6px 4px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>模型</th>
            <th>状态</th>
            <th>默认</th>
            <th>说明</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in agents" :key="a.id">
            <td class="muted">{{ a.id }}</td>
            <td><div style="display:flex;align-items:center;gap:8px"><span style="font-weight:600">{{ a.name }}</span><AgentSensitivityBadge :max-data-sensitivity="a.maxDataSensitivity" :max-risk-level="a.maxRiskLevel" :finance-hr-tool-count="a.financeHrToolCount" :confidential-tool-count="a.confidentialToolCount" /></div><div class="muted" style="font-size:12px">ref 知识 {{ (a.knowledgeRefs||[]).length }} · 工具 {{ (a.toolRefs||[]).length }} · 技能 {{ (a.skillRefs||[]).length }}</div></td>
            <td><span class="tag tag-a">{{ modelLabel(a.modelProviderId) }}</span></td>
            <td>
              <span class="tag" :class="a.enabled ? 'tag-a' : 'tag-danger'">{{ a.enabled ? '启用' : '停用' }}</span>
            </td>
            <td>{{ a.defaultAgent ? '★' : '—' }}</td>
            <td class="muted" style="max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.description || '—' }}</td>
            <td>
              <div class="row-actions">
                <button class="link-a" @click="openEdit(a)">编辑</button>
                <button v-if="canManageAgent" class="link-a" @click="openAccess(a)">授权</button>
                <button class="link-danger" @click="remove(a)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!agents.length">
            <td colspan="7" class="muted" style="text-align:center;padding:30px">暂无 Agent</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑 Agent' : '新增 Agent'" width="640px" align-center>
      <div class="form-grid">
        <div>
          <label class="form-label">名称 *</label>
          <div class="field"><input v-model="form.name" placeholder="如：客服助手" /></div>
        </div>
        <div>
          <label class="form-label">绑定模型（提供方） *</label>
          <el-select v-model="form.modelProviderId" placeholder="选择模型提供方" style="width:100%" @change="onProviderChange">
            <el-option v-for="m in models" :key="m.id" :label="modelLabel(m.id)" :value="m.id" />
          </el-select>
        </div>
        <div>
          <label class="form-label">模型名 *</label>
          <div class="field"><input v-model="form.model" placeholder="如 gpt-4o / qwen-max（选提供方后自动带出，可改）" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">说明</label>
          <div class="field"><input v-model="form.description" placeholder="一句话描述" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">系统提示词 (system prompt)</label>
          <div class="field"><textarea v-model="form.systemPrompt" placeholder="你是 SayAgent 的 AI 员工，负责……" style="min-height:110px" /></div>
        </div>
        <div>
          <label class="form-label">温度 temperature</label>
          <div class="field"><input v-model.number="form.temperature" type="number" step="0.1" min="0" max="2" /></div>
        </div>
        <div>
          <label class="form-label">topP</label>
          <div class="field"><input v-model.number="form.topP" type="number" step="0.1" min="0" max="1" /></div>
        </div>
        <div>
          <label class="form-label">maxTokens</label>
          <div class="field"><input v-model.number="form.maxTokens" type="number" min="1" /></div>
        </div>
        <div>
          <label class="form-label">上下文上限 maxContextTokens</label>
          <div class="field"><input v-model.number="form.maxContextTokens" type="number" min="1" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">知识库引用（可多选）</label>
          <el-select v-model="form.knowledgeRefs" multiple filterable placeholder="选择该 Agent 可检索的知识库" style="width:100%">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="`${kb.name} (#${kb.id})`" :value="kb.id" />
          </el-select>
          <div class="muted" style="font-size:12px;margin-top:6px" v-if="!knowledgeBases.length">暂无知识库，请先在「知识库」页创建。</div>
        </div>
        <div class="span-2">
          <label class="form-label">工具引用（MCP Server）</label>
          <el-select v-model="form.toolRefs" multiple filterable placeholder="选择该 Agent 可使用的 MCP Server" style="width:100%">
            <el-option v-for="s in mcpServers" :key="s.id" :label="`${s.name} (#${s.id})`" :value="s.id" />
          </el-select>
          <div class="muted" style="font-size:12px;margin-top:6px" v-if="!mcpServers.length">暂无 MCP Server，请先在「MCP 配置」页登记内部系统。</div>
        </div>
        <div class="span-2">
          <label class="form-label">技能引用（提示词块）</label>
          <el-select v-model="form.skillRefs" multiple filterable placeholder="选择该 Agent 挂载的技能" style="width:100%">
            <el-option v-for="sk in skills" :key="sk.id" :label="`${sk.name} (#${sk.id})`" :value="sk.id" />
          </el-select>
          <div class="muted" style="font-size:12px;margin-top:6px" v-if="!skills.length">暂无技能，请先在「技能库」页创建。</div>
        </div>
        <div>
          <label class="form-label">启用</label>
          <el-switch v-model="form.enabled" />
        </div>
        <div>
          <label class="form-label">设为默认 Agent</label>
          <el-switch v-model="form.defaultAgent" />
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="dialog = false">取消</button>
        <button class="btn-grad" :disabled="saving" @click="save">保存</button>
      </template>
    </el-dialog>

    <!-- M10/T6 Agent 授权管理：独立对话框组件（风险预览 + 强制确认 + 审计），
         仅 ADMIN 可管理；普通 Agent 授权无弹窗、高危 Agent 授权弹强制确认。 -->
    <AgentAuthorizeDialog v-model="accessVisible" :agent="accessTarget" @changed="load" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAgents, createAgent, updateAgent, deleteAgent } from '../api/agent'
import { listModels } from '../api/model'
import { listMcpServers } from '../api/mcp'
import { listBases } from '../api/knowledge'
import { listSkills } from '../api/skill'
import { useAuthStore } from '../stores/auth'
import AgentAuthorizeDialog from './AgentAuthorizeDialog.vue'
import AgentSensitivityBadge from '../components/AgentSensitivityBadge.vue'

const agents = ref([])
const models = ref([])
const mcpServers = ref([])
const knowledgeBases = ref([])
const skills = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

// ===== M10/T6 Agent 授权管理：抽出独立对话框组件（风险预览 + 强制确认 + 审计）=====
// 授权逻辑（含高危强制确认、后端审计）全部在 AgentAuthorizeDialog.vue 内，本页仅负责打开。
const auth = useAuthStore()
const canManageAgent = computed(() => (auth.roles || []).includes('ADMIN'))
const accessVisible = ref(false)
const accessTarget = ref(null)
function openAccess(a) {
  accessTarget.value = a
  accessVisible.value = true
}

const empty = () => ({
  name: '', description: '', modelProviderId: null, model: '', systemPrompt: '',
  enabled: true, defaultAgent: false,
  temperature: 0.7, topP: 1.0, maxTokens: 2048, maxContextTokens: 8000,
  knowledgeRefs: [], toolRefs: [], skillRefs: []
})
const form = ref(empty())

function modelLabel(id) {
  const m = models.value.find((x) => x.id === id)
  if (!m) return id != null ? `#${id}` : '未绑定'
  return `${m.providerType} · ${m.model || '?'} (#${m.id})`
}

// 列表页只加载「列表必需」数据：agents + models（表格里的模型标签要靠 models 渲染）。
// 不再在 onMounted 一次性并行拉 MCP/KB/技能——MCP 列表是 ADMIN 专属接口，
// 非 ADMIN 一调就 403，原 Promise.all 会让整个 load() reject，导致列表也加载不出来。
async function load() {
  const [a, m] = await Promise.all([listAgents(), listModels()])
  agents.value = a
  models.value = m
}

// 新增/编辑弹窗才需要的辅助数据：MCP Server / 知识库 / 技能。
// 各自独立 try/catch 容错：某一类接口（如非 ADMIN 拉 MCP 列表）失败不影响其余下拉框与弹窗本身。
async function loadAux() {
  try { mcpServers.value = await listMcpServers() } catch (e) { mcpServers.value = [] }
  try {
    const kb = await listBases()
    knowledgeBases.value = (kb && kb.items) || []
  } catch (e) { knowledgeBases.value = [] }
  try { skills.value = await listSkills() } catch (e) { skills.value = [] }
}

function toForm(a) {
  return {
    name: a.name, description: a.description || '', modelProviderId: a.modelProviderId, model: a.model || '',
    systemPrompt: a.systemPrompt || '', enabled: !!a.enabled, defaultAgent: !!a.defaultAgent,
    temperature: a.temperature ?? 0.7, topP: a.topP ?? 1.0,
    maxTokens: a.maxTokens ?? 2048, maxContextTokens: a.maxContextTokens ?? 8000,
    knowledgeRefs: a.knowledgeRefs || [], toolRefs: a.toolRefs || [], skillRefs: a.skillRefs || []
  }
}

function openCreate() {
  editing.value = null
  form.value = empty()
  dialog.value = true
  loadAux()
}

function onProviderChange(id) {
  const m = models.value.find((x) => x.id === id)
  form.value.model = (m && m.model) ? m.model : ''
}

function openEdit(a) {
  editing.value = a.id
  form.value = toForm(a)
  dialog.value = true
  loadAux()
}

async function save() {
  if (!form.value.name || form.value.modelProviderId == null) {
    ElMessage.warning('请填写名称并绑定模型')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form.value,
      knowledgeRefs: form.value.knowledgeRefs || [],
      toolRefs: form.value.toolRefs || []
    }
    if (editing.value == null) await createAgent(payload)
    else await updateAgent(editing.value, payload)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ }
  finally { saving.value = false }
}

async function remove(a) {
  try {
    await ElMessageBox.confirm(`确认删除 Agent「${a.name}」？关联对话不受影响。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteAgent(a.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器提示 */ }
}

onMounted(async () => {
  // 确保身份快照（roles）已加载，否则 ADMIN 判定会漏判（Agents 页本身不带角色守卫）
  if (!auth.roles || auth.roles.length === 0) {
    await auth.fetchMe()
  }
  await load()
})

</script>

<style scoped>
.grant-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.grant-id { font-weight: 600; color: var(--accent-a); }
.grant-perms { font-size: 12px; }
.role-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.role-chk { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text); }
.person-row { display: flex; gap: 10px; max-width: 420px; }
.btn-sm { padding: 6px 14px; font-size: 13px; }
.tag-role { background: rgba(124, 92, 255, 0.16); color: #9d86ff; border-color: rgba(124, 92, 255, 0.4); }
.tag-user { background: rgba(45, 212, 191, 0.16); color: #2dd4bf; border-color: rgba(45, 212, 191, 0.4); }
</style>
