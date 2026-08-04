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
