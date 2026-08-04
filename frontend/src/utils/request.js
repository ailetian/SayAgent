import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken } from './token'
import router from '../router'
import { useAuthStore } from '../stores/auth'

// 统一请求封装：自动带 Authorization 头、解析 {code,data,message}（§3.5）。
// 当前阶段为普通 axios；SSE 由 F5 的 fetch 单独处理，不在此处。
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截：注入 Bearer token
request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      // 用 AxiosHeaders.set 而非直接属性赋值：axios 1.x 下直接赋值可能被序列化丢弃
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：统一解析统一响应体
request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 兼容统一响应盒 {code,data,message}
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    // 非统一结构直接返回原始 data
    return body
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const msg = (body && body.message) || error.message || '网络错误'

    if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      // 已在登录页时不再跳回登录，由页面内联红字提示
      if (router.currentRoute.value.name !== 'login') {
        router.push('/login')
      } else {
        ElMessage.error(msg)
      }
    } else {
      ElMessage.error(msg)
    }
    // 把后端 message 透传给调用方 catch
    return Promise.reject(new Error(msg))
  }
)

export default request
