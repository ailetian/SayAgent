<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">技能库</div>
        <div class="page-sub">技能是「可复用的提示词块」，挂到 Agent 后会在每次对话拼进系统人设（行为/指令复用，区别于 MCP 执行动作）。</div>
      </div>
      <button class="btn-grad" @click="openCreate">+ 新增技能</button>
    </div>

    <div class="glass" style="padding: 6px 4px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>说明</th>
            <th>提示词（预览）</th>
            <th>状态</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in skills" :key="s.id">
            <td class="muted">{{ s.id }}</td>
            <td>{{ s.name || '—' }}</td>
            <td class="muted" style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ s.description || '—' }}</td>
            <td class="muted" style="max-width:380px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ s.promptText || '—' }}</td>
            <td>
              <el-switch :model-value="s.enabled !== false" @change="(v) => toggleEnabled(s, v)" />
            </td>
            <td>
              <div class="row-actions">
                <button class="link-a" @click="openEdit(s)">编辑</button>
                <button class="link-danger" @click="remove(s)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!skills.length">
            <td colspan="6" class="muted" style="text-align:center;padding:30px">暂无技能，点击右上角「新增技能」创建第一条提示词技能。</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑技能' : '新增技能'" width="600px" align-center>
      <div class="form-grid span-2">
        <div class="span-2">
          <label class="form-label">名称 *</label>
          <div class="field"><input v-model="form.name" placeholder="如：大白话术语" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">说明</label>
          <div class="field"><input v-model="form.description" placeholder="一句话描述这个技能做什么" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">提示词（promptText）* —— 挂到 Agent 后会拼进人设</label>
          <div class="field"><textarea v-model="form.promptText" placeholder="例如：无论用户问什么，先判断其中是否含专业术语；若有，先用一句大白话解释该术语，再正式回答。" style="min-height:140px" /></div>
        </div>
        <div>
          <label class="form-label">启用</label>
          <el-switch v-model="form.enabled" />
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
import { listSkills, createSkill, updateSkill, deleteSkill } from '../api/skill'

const skills = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

const empty = () => ({ name: '', description: '', promptText: '', enabled: true })
const form = ref(empty())

async function load() {
  skills.value = await listSkills()
}

function openCreate() {
  editing.value = null
  form.value = empty()
  dialog.value = true
}

function openEdit(s) {
  editing.value = s.id
  form.value = {
    name: s.name || '',
    description: s.description || '',
    promptText: s.promptText || '',
    enabled: s.enabled !== false
  }
  dialog.value = true
}

async function save() {
  if (!form.value.name || !form.value.promptText) {
    ElMessage.warning('请填写名称与提示词')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      description: form.value.description,
      promptText: form.value.promptText,
      enabled: form.value.enabled
    }
    if (editing.value == null) {
      await createSkill(payload)
      ElMessage.success('已新增')
    } else {
      await updateSkill(editing.value, payload)
      ElMessage.success('已保存')
    }
    dialog.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ }
  finally { saving.value = false }
}

async function toggleEnabled(s, val) {
  try {
    await updateSkill(s.id, {
      name: s.name,
      description: s.description,
      promptText: s.promptText,
      enabled: val
    })
    s.enabled = val
    ElMessage.success(val ? '已启用' : '已停用')
  } catch (e) { /* 拦截器提示 */ }
}

async function remove(s) {
  try {
    await ElMessageBox.confirm(`确认删除技能「${s.name || s.id}」？已挂载该技能的 Agent 将不再获得此指令。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteSkill(s.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器提示 */ }
}

onMounted(load)
</script>
