import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as tokenUtil from '../utils/token'
import request from '../utils/request'

const USER_KEY = 'hify_user'

// 登录态：token + 用户信息，持久化到 localStorage（刷新后仍在）。
// 注意：user 中只包含后端 LoginResponse 的 {username, role}，绝不缓存 password。
// T4 新增：roles（角色数组）+ menus（可见菜单视图），由 /api/me 动态下发，
// 用于侧边栏动态渲染与路由 meta.roles 守卫，不再前端写死。
export const useAuthStore = defineStore('auth', () => {
  const token = ref(tokenUtil.getToken())
  const user = ref(readUser())
  const roles = ref([]) // 角色数组，如 ['ADMIN']（M9/T4 菜单轴）
  const menus = ref([]) // 可见菜单视图 [{code,title,route,icon}]，侧边栏数据源

  const isLoggedIn = computed(() => !!token.value)

  function login(newToken, newUser) {
    token.value = newToken
    user.value = newUser
    tokenUtil.setToken(newToken)
    localStorage.setItem(USER_KEY, JSON.stringify(newUser))
  }

  // 拉取当前登录人身份快照（角色 + 可见菜单），§3.5 响应契约。
  // 登录后、或 app 启动（已登录）时调用；失败（如 token 过期）由 request 拦截器统一登出/跳转，这里静默。
  async function fetchMe() {
    if (!token.value) return
    try {
      const data = await request.get('/me') // 返回 MeResponse {role, roles, menus}
      if (data && Array.isArray(data.roles)) roles.value = data.roles
      if (data && Array.isArray(data.menus)) menus.value = data.menus
    } catch {
      // 拦截器已处理 401 登出/跳转，无需在此抛错
    }
  }

  function logout() {
    token.value = null
    user.value = null
    roles.value = []
    menus.value = []
    tokenUtil.removeToken()
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, roles, menus, isLoggedIn, login, fetchMe, logout }
})

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}
