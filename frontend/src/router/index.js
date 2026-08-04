import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/Chat.vue'),
    meta: { requiresAuth: true, layout: 'default', title: '对话' }
  },
  {
    path: '/agents',
    name: 'agents',
    component: () => import('../views/Agents.vue'),
    meta: { requiresAuth: true, layout: 'default', title: 'Agent 配置' }
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { requiresAuth: true, layout: 'default', title: '知识库' }
  },
  {
    path: '/models',
    name: 'models',
    component: () => import('../views/Models.vue'),
    meta: { requiresAuth: true, layout: 'default', title: '模型管理' }
  },
  {
    path: '/mcp',
    name: 'mcp',
    component: () => import('../views/McpServer.vue'),
    meta: { requiresAuth: true, layout: 'default', title: 'MCP 配置' }
  },
  { path: '/', redirect: '/chat' },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：未登录访问受保护路由 -> /login；已登录访问 /login -> /chat
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'chat' }
  }
  return true
})

export default router
