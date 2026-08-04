import request from '../utils/request'

// 调 M2 AuthController: POST /api/auth/login
// 请求体 { username, password }；返回 data = { token, username, role }（不含 password，§7.11）
export function login(username, password) {
  return request.post('/auth/login', { username, password })
}
