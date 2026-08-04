<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">模型管理</div>
        <div class="page-sub">配置 LLM 提供方。密钥仅回传服务端，列表不展示明文（密钥隔离）。</div>
      </div>
      <button class="btn-grad" @click="openCreate">+ 新增提供方</button>
    </div>

    <div class="glass" style="padding: 6px 4px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>类型</th>
            <th>Base URL</th>
            <th>模型</th>
            <th>状态</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td class="muted">{{ m.id }}</td>
            <td>{{ m.name || '—' }}</td>
            <td><span class="tag tag-a">{{ m.providerType }}</span></td>
            <td class="muted" style="max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ m.apiUrl || '—' }}</td>
            <td>{{ m.model || '—' }}</td>
            <td><span class="tag" :class="m.enabled ? 'tag-b' : 'tag-danger'">{{ m.enabled ? '启用' : '停用' }}</span></td>
            <td>
              <div class="row-actions">
                <button class="link-a" @click="openEdit(m)">编辑</button>
                <button class="link-danger" @click="remove(m)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!models.length">
            <td colspan="7" class="muted" style="text-align:center;padding:30px">暂无模型提供方</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑提供方' : '新增提供方'" width="520px" align-center>
      <div class="form-grid span-2">
        <div class="span-2">
          <label class="form-label">名称</label>
          <div class="field"><input v-model="form.name" placeholder="如 公司OpenAI" /></div>
        </div>
        <div :class="editing ? '' : 'span-2'">
          <label class="form-label">提供方类型</label>
          <el-select v-model="form.providerType" :disabled="editing" placeholder="选择类型" style="width:100%">
            <el-option v-for="t in providerTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </div>
        <div v-if="editing" />
        <div class="span-2">
          <label class="form-label">Base URL</label>
          <div class="field"><input v-model="form.apiUrl" placeholder="https://api.openai.com/v1" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">模型名</label>
          <div class="field"><input v-model="form.model" placeholder="gpt-4o / qwen-max ..." /></div>
        </div>
        <div class="span-2">
          <label class="form-label">
            API Key / 密钥
            <span v-if="editing" class="muted">（留空表示不修改）</span>
          </label>
          <div class="field"><input v-model="form.secret" type="password" :placeholder="editing ? '不修改请留空' : '服务端加密存储'" autocomplete="off" /></div>
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
import { listModels, createModel, updateModel, deleteModel } from '../api/model'

const models = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
// 必须与后端 ProviderType 枚举保持一致（§3.2/§4.5）：OPENAI / CLAUDE / GEMINI / OLLAMA
const providerTypes = ['OPENAI', 'CLAUDE', 'GEMINI', 'OLLAMA']

// 字段名必须对齐后端 ProviderCreateRequest / ProviderUpdateRequest：
// name(必填) / apiUrl(必填) / secret(密钥) / providerType(必填) / model(可选)
const empty = () => ({ providerType: 'OPENAI', name: '', apiUrl: '', model: '', secret: '' })
const form = ref(empty())

async function load() {
  models.value = await listModels()
}

function openCreate() {
  editing.value = null
  form.value = empty()
  dialog.value = true
}

function openEdit(m) {
  editing.value = m.id
  form.value = { providerType: m.providerType, name: m.name || '', apiUrl: m.apiUrl || '', model: m.model || '', secret: '' }
  dialog.value = true
}

async function save() {
  saving.value = true
  try {
    const payload = {
      providerType: form.value.providerType,
      name: form.value.name,
      apiUrl: form.value.apiUrl,
      model: form.value.model
    }
    // secret 仅在有填写时上传（编辑时留空 = 不修改）
    if (form.value.secret) payload.secret = form.value.secret
    if (editing.value == null) {
      await createModel(payload)
      ElMessage.success('已新增')
    } else {
      await updateModel(editing.value, payload)
      ElMessage.success('已保存')
    }
    dialog.value = false
    await load()
  } catch (e) {
    /* 错误提示由 request 拦截器统一处理 */
  } finally {
    saving.value = false
  }
}

async function remove(m) {
  try {
    await ElMessageBox.confirm(`确认删除提供方 #${m.id}（${m.name || m.providerType}）？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteModel(m.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器提示 */ }
}

onMounted(load)
</script>
