import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as tokenUtil from '../utils/token'

const USER_KEY = 'hify_user'

// 登录态：token + 用户信息，持久化到 localStorage（刷新后仍在）。
// 注意：user 中只包含后端 LoginResponse 的 {username, role}，绝不缓存 password。
export const useAuthStore = defineStore('auth', () => {
  const token = ref(tokenUtil.getToken())
  const user = ref(readUser())

  const isLoggedIn = computed(() => !!token.value)

  function login(newToken, newUser) {
    token.value = newToken
    user.value = newUser
    tokenUtil.setToken(newToken)
    localStorage.setItem(USER_KEY, JSON.stringify(newUser))
  }

  function logout() {
    token.value = null
    user.value = null
    tokenUtil.removeToken()
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, isLoggedIn, login, logout }
})

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}
