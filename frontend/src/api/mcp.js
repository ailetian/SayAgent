import request from '../utils/request'

// MCP Server 配置（后端 M7 /api/mcp/servers，返回普通 List，非 keyset）
// 仅 ADMIN 可增删改（后端 McpServerServiceImpl.assertAdmin 拦截），GET 任意已登录用户可读。
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
