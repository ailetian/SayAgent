<template>
  <el-dialog
    v-model="visible"
    :title="agent ? ('授权管理 — ' + agent.name) : '授权管理'"
    width="640px" align-center
    @closed="onClosed">

    <div v-if="!canManageAgent" class="muted" style="padding:10px 2px">
      仅管理员可以管理 Agent 授权。
    </div>

    <div v-else>
      <p v-if="grantError" class="error-text" style="margin-bottom:10px">{{ grantError }}</p>

      <!-- T6 风险预览卡：后端聚合，前端不自行计算（§3.2 后端为真相源） -->
      <div class="form-label">本 Agent 携带工具（风险预览）</div>
      <div v-if="toolSens.length === 0" class="muted" style="font-size:12px">
        该 Agent 未挂载任何 MCP 工具（仅内置工具，常规权限）。
      </div>
      <div v-else class="risk-preview">
        <div v-for="t in toolSens" :key="t.name" class="tp-item">
          <span class="tp-name">{{ t.name }}</span>
          <ToolRiskBadge :risk-level="t.riskLevel" :data-sensitivity="t.dataSensitivity" />
        </div>
      </div>
      <div v-if="isHighRisk" class="warn-banner">
        ⚠ 该 Agent 含敏感 / 高危工具，授权将触发二次确认并写入审计日志。
      </div>

      <div class="form-label" style="margin-top:18px">当前授权</div>
      <div v-if="grants.length === 0" class="muted">暂无授权，仅创建者（管理员）本人可见。</div>
      <div v-for="g in grants" :key="g.principalType + ':' + g.principalId" class="grant-row">
        <span class="tag" :class="g.principalType === 'ROLE' ? 'tag-role' : 'tag-user'">{{ g.principalType }}</span>
        <span class="grant-id">{{ g.principalId }}</span>
        <span class="muted grant-perms">{{ permText(g) }}</span>
        <button class="btn-ghost btn-sm" :disabled="busy" @click="revokeGrant(g)">撤销</button>
      </div>

      <div class="form-label" style="margin-top:18px">按角色批量授权（可读 / 可用）</div>
      <div class="muted" style="font-size:12px;margin:2px 0 8px">ADMIN 默认拥有全部权限，无需授权。</div>
      <div class="role-row">
        <label class="role-chk"><input type="checkbox" v-model="roleChecks.OPERATOR" /> OPERATOR</label>
        <label class="role-chk"><input type="checkbox" v-model="roleChecks.USER" /> USER</label>
        <button class="btn-grad btn-sm" :disabled="busy" @click="batchGrantRoles">授权选中角色</button>
      </div>

      <div class="form-label" style="margin-top:18px">添加个人（可读 / 可用）</div>
      <div class="person-row">
        <input class="field" v-model="newUser" placeholder="输入用户名" @keyup.enter="addUser" />
        <button class="btn-grad btn-sm" :disabled="busy || !newUser" @click="addUser">添加</button>
      </div>
    </div>

    <!-- T6 强制确认模态框：仅当 Agent 含 L2/L3 或 CONFIDENTIAL/FINANCE_HR 工具时弹出 -->
    <el-dialog
      v-model="confirmVisible"
      title="高危授权确认"
      width="520px" align-center
      append-to-body>
      <div class="muted" style="margin-bottom:10px">
        你正在把包含 <b>敏感 / 高危工具</b> 的 Agent「<b>{{ agent && agent.name }}</b>」授权给 <b>{{ confirmTargetLabel }}</b>。
      </div>
      <div class="risk-list">
        <div v-for="t in sensitiveTools" :key="t.name" class="risk-item">
          <span class="ri-name">{{ t.name }}</span>
          <ToolRiskBadge :risk-level="t.riskLevel" :data-sensitivity="t.dataSensitivity" />
        </div>
      </div>
      <div class="error-text" style="margin-top:10px">
        确认后将立即授权并写入审计日志。请确认目标权限确实必要。
      </div>
      <template #footer>
        <button class="btn-ghost" @click="confirmVisible = false">取消</button>
        <button class="btn-grad" :disabled="busy" @click="confirmGrantFromModal">确认授权</button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getAgentTools, grantAgentAccess } from '../api/agent'
import { listGrants, revokeAccess } from '../api/knowledge'
import { useAuthStore } from '../stores/auth'
import ToolRiskBadge from '../components/ToolRiskBadge.vue'

const props = defineProps({
  // 目标 Agent（含 AgentVO 聚合敏感度字段）
  agent: { type: Object, default: null },
  // v-model 可见性
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const auth = useAuthStore()
const canManageAgent = computed(() => (auth.roles || []).includes('ADMIN'))

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const grants = ref([])
const grantError = ref('')
const toolSens = ref([])
const newUser = ref('')
const roleChecks = reactive({ OPERATOR: false, USER: false })
const busy = ref(false)

// 强制确认弹窗状态
const confirmVisible = ref(false)
const pendingPayloads = ref([])
const confirmTargetLabel = ref('')

// 触发强制确认的高危 / 敏感判定（与后端 T5 执行闸、T6 触发条件一致）
function isSensitive(t) {
  return t.riskLevel === 'L2_IRREVERSIBLE' || t.riskLevel === 'L3_HIGH_RISK' ||
    t.dataSensitivity === 'CONFIDENTIAL' || t.dataSensitivity === 'FINANCE_HR'
}
const isHighRisk = computed(() => (toolSens.value || []).some(isSensitive))
const sensitiveTools = computed(() => (toolSens.value || []).filter(isSensitive))

function permText(g) {
  const ps = []
  if (g.canRead) ps.push('读')
  if (g.canWrite) ps.push('写')
  if (g.canUse) ps.push('用')
  if (g.canEdit) ps.push('编')
  return ps.length ? ps.join('/') : '无'
}

async function loadData() {
  if (!props.agent) return
  grantError.value = ''
  try {
    const [g, t] = await Promise.all([
      listGrants('AGENT', props.agent.id),
      getAgentTools(props.agent.id)
    ])
    grants.value = g || []
    toolSens.value = t || []
  } catch (e) {
    grantError.value = e.message || '加载失败'
  }
}

watch(() => props.modelValue, (v) => { if (v) loadData() })

// 统一授权入口：高危则弹强制确认，否则直接提交
function requestGrant(payloads, targetLabel) {
  if (!canManageAgent.value) {
    grantError.value = '仅管理员可以管理 Agent 授权。'
    return
  }
  if (isHighRisk.value) {
    pendingPayloads.value = payloads
    confirmTargetLabel.value = targetLabel
    confirmVisible.value = true
  } else {
    submitGrants(payloads)
  }
}

async function submitGrants(payloads) {
  busy.value = true
  grantError.value = ''
  try {
    for (const p of payloads) {
      await grantAgentAccess(props.agent.id, p)
    }
    ElMessage.success('已授权')
    confirmVisible.value = false
    pendingPayloads.value = []
    await loadData()
    emit('changed')
  } catch (e) {
    grantError.value = e.message || '授权失败'
  } finally {
    busy.value = false
  }
}

function confirmGrantFromModal() {
  if (pendingPayloads.value && pendingPayloads.value.length) {
    submitGrants(pendingPayloads.value)
  }
}

function batchGrantRoles() {
  const selected = Object.keys(roleChecks).filter((r) => roleChecks[r])
  if (selected.length === 0) {
    grantError.value = '请先勾选至少一个角色'
    return
  }
  const payloads = selected.map((r) => ({
    principalType: 'ROLE', principalId: r,
    canRead: true, canWrite: false, canUse: true, canEdit: false
  }))
  requestGrant(payloads, '角色 ' + selected.join('、'))
  roleChecks.OPERATOR = false
  roleChecks.USER = false
}

function addUser() {
  if (!newUser.value) return
  const name = newUser.value.trim()
  const payloads = [{
    principalType: 'USER', principalId: name,
    canRead: true, canWrite: false, canUse: true, canEdit: false
  }]
  requestGrant(payloads, '用户 ' + name)
  newUser.value = ''
}

async function revokeGrant(g) {
  busy.value = true
  grantError.value = ''
  try {
    await revokeAccess({
      principalType: g.principalType, principalId: g.principalId,
      resourceType: 'AGENT', resourceId: props.agent.id
    })
    await loadData()
    emit('changed')
  } catch (e) {
    grantError.value = e.message || '撤销失败'
  } finally {
    busy.value = false
  }
}

function onClosed() {
  // 关闭时清理，避免下次打开残留
  grantError.value = ''
  toolSens.value = []
  grants.value = []
}
</script>

<style scoped>
.risk-preview {
  background: rgba(0, 0, 0, 0.28);
  border: 1px solid rgba(245, 245, 240, 0.14);
  border-radius: 12px;
  padding: 10px 12px;
  display: flex; flex-direction: column; gap: 8px;
}
.tp-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.tp-name { font-weight: 600; color: var(--text); font-size: 13px; }
.warn-banner {
  margin-top: 10px; font-size: 12px; color: #FF7A6B;
  background: rgba(255, 122, 107, 0.12); border: 1px solid rgba(255, 122, 107, 0.4);
  border-radius: 10px; padding: 8px 10px;
}
.grant-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.grant-id { font-weight: 600; color: var(--accent-a); }
.grant-perms { font-size: 12px; }
.role-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.role-chk { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text); }
.person-row { display: flex; gap: 10px; max-width: 420px; }
.btn-sm { padding: 6px 14px; font-size: 13px; }
.tag-role { background: rgba(124, 92, 255, 0.16); color: #9d86ff; border-color: rgba(124, 92, 255, 0.4); }
.tag-user { background: rgba(45, 212, 191, 0.16); color: #2dd4bf; border-color: rgba(45, 212, 191, 0.4); }
.risk-list { display: flex; flex-direction: column; gap: 8px; max-height: 240px; overflow: auto; }
.risk-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ri-name { font-weight: 600; color: var(--text); font-size: 13px; }
</style>
