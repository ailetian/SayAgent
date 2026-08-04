<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">MCP 配置</div>
        <div class="page-sub">登记内部系统的 MCP Server 地址，Agent 即可自动发现并调用其工具（增删改仅管理员）。</div>
      </div>
      <button v-if="isAdmin" class="btn-grad" @click="openCreate">+ 新增 Server</button>
    </div>

    <el-alert
      v-if="!isAdmin"
      type="info"
      :closable="false"
      show-icon
      title="当前账号非管理员，仅可查看 MCP Server 列表；增删改需管理员权限。"
      style="margin-bottom: 14px"
    />

    <div class="glass" style="padding: 6px 4px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>地址</th>
            <th>类型</th>
            <th>状态</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in servers" :key="s.id">
            <td class="muted">{{ s.id }}</td>
            <td>{{ s.name || '—' }}</td>
            <td class="muted" style="max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ s.address || '—' }}</td>
            <td><span class="tag tag-a">{{ s.type }}</span></td>
            <td><span class="tag" :class="s.status === 1 ? 'tag-b' : 'tag-danger'">{{ s.status === 1 ? '启用' : '停用' }}</span></td>
            <td>
              <div class="row-actions" v-if="isAdmin">
                <button class="link-a" @click="openEdit(s)">编辑</button>
                <button class="link-danger" @click="remove(s)">删除</button>
              </div>
              <span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!servers.length">
            <td colspan="6" class="muted" style="text-align:center;padding:30px">暂无 MCP Server，点击右上角「新增 Server」登记内部系统。</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑 Server' : '新增 Server'" width="520px" align-center>
      <div class="form-grid span-2">
        <div class="span-2">
          <label class="form-label">名称 *</label>
          <div class="field"><input v-model="form.name" placeholder="如：订单系统" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">地址 *</label>
          <div class="field"><input v-model="form.address" placeholder="http://order.internal:8080/mcp 或 STDIO 命令" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">类型 *</label>
          <el-select v-model="form.type" placeholder="选择连接方式" style="width:100%">
            <el-option v-for="t in types" :key="t" :label="t" :value="t" />
          </el-select>
        </div>
        <div class="span-2">
          <label class="form-label">状态</label>
          <el-select v-model="form.status" style="width:100%">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
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
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMcpServers, createMcpServer, updateMcpServer, deleteMcpServer } from '../api/mcp'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
// 后端 McpServerServiceImpl.assertAdmin 拦截增删改，前端仅作 UI 收口（非安全边界）。
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

const servers = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
// 必须与后端 McpServerCreateReq.type 的 @Pattern 一致：STDIO / SSE / HTTP
const types = ['STDIO', 'SSE', 'HTTP']

const empty = () => ({ name: '', address: '', type: 'SSE', status: 1 })
const form = ref(empty())

async function load() {
  servers.value = await listMcpServers()
}

function openCreate() {
  editing.value = null
  form.value = empty()
  dialog.value = true
}

function openEdit(s) {
  editing.value = s.id
  form.value = { name: s.name || '', address: s.address || '', type: s.type, status: s.status === 1 ? 1 : 0 }
  dialog.value = true
}

async function save() {
  if (!form.value.name || !form.value.address || !form.value.type) {
    ElMessage.warning('请填写名称、地址与类型')
    return
  }
  saving.value = true
  try {
    const payload = { name: form.value.name, address: form.value.address, type: form.value.type, status: form.value.status }
    if (editing.value == null) {
      await createMcpServer(payload)
      ElMessage.success('已新增')
    } else {
      await updateMcpServer(editing.value, payload)
      ElMessage.success('已保存')
    }
    dialog.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ }
  finally { saving.value = false }
}

async function remove(s) {
  try {
    await ElMessageBox.confirm(`确认删除 MCP Server「${s.name || s.id}」？已关联该 Server 的 Agent 将失去工具调用能力。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteMcpServer(s.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器提示 */ }
}

onMounted(load)
</script>
