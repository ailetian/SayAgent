import request from '../utils/request'

// 知识库（后端 M5 /api/knowledge）
// 注意：当前后端仅提供 upload / retrieve / access 三类接口，
// 无「列出知识库 / 列出已上传文档」端点（见 F4 验收报告），故前端无 listDocs。

// 上传文档：JSON body（KnowledgeBaseUploadRequest），非 multipart FormData
export function uploadDoc(payload) {
  return request.post('/knowledge/upload', payload)
}

// 检索：RetrieveRequest { query, kbId, topK }
export function retrieve(payload) {
  return request.post('/knowledge/retrieve', payload)
}

// 授权 / 查询 / 撤销（保留以备扩展）
export function grantAccess(kbId, userId) {
  return request.post(`/knowledge/${kbId}/access/${userId}`)
}
export function listAccess(kbId) {
  return request.get(`/knowledge/${kbId}/access`)
}
export function revokeAccess(kbId, userId) {
  return request.delete(`/knowledge/${kbId}/access/${userId}`)
}
