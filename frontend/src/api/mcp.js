import request from '../utils/request'

// MCP Server 配置（后端 M7 /api/mcp/servers，返回普通 List，非 keyset）
// 增删改查均仅 ADMIN 可访问（后端 McpServerServiceImpl.assertAdmin 拦截，含 listServers 查询）。
// 注意：MCP 列表非全员可读，故 Agents.vue 仅在 ADMIN/OPERATOR 打开新增/编辑弹窗时按需拉取并容错。
export function listMcpServers() {
  return request.get('/mcp/servers')
}

export function createMcpServer(payload) {
  return request.post('/mcp/servers', payload)
}

export function updateMcpServer(id, payload) {
  return request.put(`/mcp/servers/${id}`, payload)
}

export function deleteMcpServer(id) {
  return request.delete(`/mcp/servers/${id}`)
}
