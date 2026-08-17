import request from '../utils/request'

// Agent 配置（后端 M4 /api/agents，返回普通 List，非 keyset）
export function listAgents() {
  return request.get('/agents')
}

export function createAgent(payload) {
  return request.post('/agents', payload)
}

export function updateAgent(id, payload) {
  return request.put(`/agents/${id}`, payload)
}

export function deleteAgent(id) {
  return request.delete(`/agents/${id}`)
}

// ===== M10/T6 前端防误授权端点（后端 AgentService 受理，含风险摘要审计）=====

// 风险预览：列出某 Agent 携带的每个工具的危险度 + 数据敏感度快照（GET /api/agents/{id}/tools）
export function getAgentTools(agentId) {
  return request.get(`/agents/${agentId}/tools`)
}

// 授权某 Agent 给某主体（POST /api/agents/{id}/access）；后端计算风险摘要并写审计（§7.11）。
// 仅 ADMIN 或该 Agent 管理者可调用（后端 requireManager 硬闸，不信任前端标记，§4）。
export function grantAgentAccess(agentId, payload) {
  return request.post(`/agents/${agentId}/access`, payload)
}
