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
            <td><div style="font-weight:600">{{ a.name }}</div><div class="muted" style="font-size:12px">ref 知识 {{ (a.knowledgeRefs||[]).length }} · 工具 {{ (a.toolRefs||[]).length }}</div></td>
            <td><span class="tag tag-a">{{ modelLabel(a.modelProviderId) }}</span></td>
            <td>
              <span class="tag" :class="a.enabled ? 'tag-a' : 'tag-danger'">{{ a.enabled ? '启用' : '停用' }}</span>
            </td>
            <td>{{ a.defaultAgent ? '★' : '—' }}</td>
            <td class="muted" style="max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.description || '—' }}</td>
            <td>
              <div class="row-actions">
                <button class="link-a" @click="openEdit(a)">编辑</button>
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
          <label class="form-label">绑定模型 *</label>
          <el-select v-model="form.modelProviderId" placeholder="选择模型提供方" style="width:100%">
            <el-option v-for="m in models" :key="m.id" :label="modelLabel(m.id)" :value="m.id" />
          </el-select>
        </div>
        <div class="span-2">
          <label class="form-label">说明</label>
          <div class="field"><input v-model="form.description" placeholder="一句话描述" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">系统提示词 (system prompt)</label>
          <div class="field"><textarea v-model="form.systemPrompt" placeholder="你是 Hify 的 AI 员工，负责……" style="min-height:110px" /></div>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAgents, createAgent, updateAgent, deleteAgent } from '../api/agent'
import { listModels } from '../api/model'
import { listMcpServers } from '../api/mcp'
import { listBases } from '../api/knowledge'

const agents = ref([])
const models = ref([])
const mcpServers = ref([])
const knowledgeBases = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

const empty = () => ({
  name: '', description: '', modelProviderId: null, systemPrompt: '',
  enabled: true, defaultAgent: false,
  temperature: 0.7, topP: 1.0, maxTokens: 2048, maxContextTokens: 8000,
  knowledgeRefs: [], toolRefs: []
})
const form = ref(empty())

function modelLabel(id) {
  const m = models.value.find((x) => x.id === id)
  if (!m) return id != null ? `#${id}` : '未绑定'
  return `${m.providerType} · ${m.model || '?'} (#${m.id})`
}

async function load() {
  const [a, m, ms, kb] = await Promise.all([
    listAgents(), listModels(), listMcpServers(), listBases()
  ])
  agents.value = a
  models.value = m
  mcpServers.value = ms
  knowledgeBases.value = (kb && kb.items) || []
}

function toForm(a) {
  return {
    name: a.name, description: a.description || '', modelProviderId: a.modelProviderId,
    systemPrompt: a.systemPrompt || '', enabled: !!a.enabled, defaultAgent: !!a.defaultAgent,
    temperature: a.temperature ?? 0.7, topP: a.topP ?? 1.0,
    maxTokens: a.maxTokens ?? 2048, maxContextTokens: a.maxContextTokens ?? 8000,
    knowledgeRefs: a.knowledgeRefs || [], toolRefs: a.toolRefs || []
  }
}

function openCreate() {
  editing.value = null
  form.value = empty()
  dialog.value = true
}

function openEdit(a) {
  editing.value = a.id
  form.value = toForm(a)
  dialog.value = true
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

onMounted(load)
</script>
