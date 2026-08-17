<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">用户管理</div>
        <div class="page-sub">创建团队成员并分配角色（ADMIN / OPERATOR / USER）。密码经 BCrypt 加密存储，列表不展示明文（密钥隔离，§7.11）。</div>
      </div>
      <button class="btn-grad" @click="openCreate">+ 新建用户</button>
    </div>

    <div class="glass" style="padding: 6px 4px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>角色</th>
            <th>显示名</th>
            <th>邮箱</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.username">
            <td>{{ u.username }}</td>
            <td><span class="tag" :class="roleClass(u.role)">{{ u.role || '—' }}</span></td>
            <td class="muted">{{ u.displayName || '—' }}</td>
            <td class="muted">{{ u.email || '—' }}</td>
          </tr>
          <tr v-if="!users.length">
            <td colspan="4" class="muted" style="text-align:center;padding:30px">暂无用户</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialog" title="新建用户" width="520px" align-center>
      <div class="form-grid span-2">
        <div class="span-2">
          <label class="form-label">用户名 <span class="error-text">*</span></label>
          <div class="field"><input v-model="form.username" placeholder="登录账号，唯一" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">密码 <span class="error-text">*</span></label>
          <div class="field"><input v-model="form.password" type="password" placeholder="服务端 BCrypt 加密存储" autocomplete="new-password" /></div>
        </div>
        <div class="span-2">
          <label class="form-label">角色</label>
          <el-select v-model="form.role" placeholder="选择角色" style="width:100%">
            <el-option v-for="r in roles" :key="r" :label="r" :value="r" />
          </el-select>
        </div>
        <div>
          <label class="form-label">显示名</label>
          <div class="field"><input v-model="form.displayName" placeholder="可选" /></div>
        </div>
        <div>
          <label class="form-label">邮箱</label>
          <div class="field"><input v-model="form.email" placeholder="可选" /></div>
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="dialog = false">取消</button>
        <button class="btn-grad" :disabled="saving || !canSave" @click="save">保存</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listUsers, createUser } from '../api/users'

const users = ref([])
const dialog = ref(false)
const saving = ref(false)
// 必须与后端 UserRole 枚举保持一致（§2.1，三档）：ADMIN / OPERATOR / USER
const roles = ['ADMIN', 'OPERATOR', 'USER']

// 字段名对齐后端 CreateUserRequest：username / password / role / displayName / email
const empty = () => ({ username: '', password: '', role: 'USER', displayName: '', email: '' })
const form = ref(empty())

const canSave = computed(() => !!form.value.username && !!form.value.password)

async function load() {
  users.value = await listUsers()
}

function openCreate() {
  form.value = empty()
  dialog.value = true
}

function roleClass(role) {
  if (role === 'ADMIN') return 'tag-a'
  if (role === 'OPERATOR') return 'tag-b'
  return ''
}

async function save() {
  if (!canSave.value) {
    ElMessage.warning('用户名与密码为必填')
    return
  }
  saving.value = true
  try {
    const payload = {
      username: form.value.username,
      password: form.value.password,
      role: form.value.role
    }
    if (form.value.displayName) payload.displayName = form.value.displayName
    if (form.value.email) payload.email = form.value.email
    await createUser(payload)
    ElMessage.success('已创建')
    dialog.value = false
    await load()
  } catch (e) {
    /* 错误提示由 request 拦截器统一处理（如 username 重复、非 ADMIN 403） */
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
