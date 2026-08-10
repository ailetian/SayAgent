import request from '../utils/request'

// 技能库（后端 M8/T4 提示词式 /api/skills，返回普通 List，非 keyset）
// 技能是「可复用的提示词块」，含 name/description/promptText/enabled。
export function listSkills() {
  return request.get('/skills')
}

export function createSkill(payload) {
  return request.post('/skills', payload)
}

export function updateSkill(id, payload) {
  return request.put(`/skills/${id}`, payload)
}

export function deleteSkill(id) {
  return request.delete(`/skills/${id}`)
}
