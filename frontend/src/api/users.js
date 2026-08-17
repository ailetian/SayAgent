import request from '../utils/request'

// 用户管理（后端 M9/T3 /api/users，§3.5 统一响应盒；响应脱敏无 password，§7.11）。
// request 拦截器已把统一盒 {code,data,message} 解成 data，故这里直接返回列表/对象。
export function listUsers() {
  return request.get('/users')
}

// 建用户：body 须含 username/password，role 可空（后端缺省 USER）。
// 字段名对齐后端 CreateUserRequest：username / password / role / displayName / email。
export function createUser(payload) {
  return request.post('/users', payload)
}
