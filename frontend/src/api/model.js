import request from '../utils/request'

// 模型管理（后端 M3 /api/models，返回普通 List，非 keyset）
export function listModels() {
  return request.get('/models')
}

export function createModel(payload) {
  return request.post('/models', payload)
}

export function updateModel(id, payload) {
  return request.put(`/models/${id}`, payload)
}

export function deleteModel(id) {
  return request.delete(`/models/${id}`)
}
