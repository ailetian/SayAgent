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
    component: () => import('../views/KnowledgeList.vue'),
    meta: { requiresAuth: true, layout: 'default', title: '知识库' }
  },
  {
    path: '/knowledge/:kbId',
    name: 'knowledge-detail',
    component: () => import('../views/KnowledgeDetail.vue'),
    meta: { requiresAuth: true, layout: 'default', title: '知识库详情' }
  },
  {
    path: '/models',
    name: 'models',
    component: () => import('../views/Models.vue'),
    // 仅 ADMIN（M9/T4 角色菜单轴，§2.1）
    meta: { requiresAuth: true, layout: 'default', title: '模型管理', roles: ['ADMIN'] }
  },
  {
    path: '/mcp',
    name: 'mcp',
    component: () => import('../views/McpServer.vue'),
    // 仅 ADMIN
    meta: { requiresAuth: true, layout: 'default', title: 'MCP 配置', roles: ['ADMIN'] }
  },
  {
    path: '/skills',
    name: 'skills',
    component: () => import('../views/Skills.vue'),
    // ADMIN + OPERATOR（种子数据：skills 仅 ADMIN/OPERATOR）
    meta: { requiresAuth: true, layout: 'default', title: '技能库', roles: ['ADMIN', 'OPERATOR'] }
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('../views/Users.vue'),
    // 仅 ADMIN（M9/T8 用户管理页，§2.1；菜单种子 V31 已含 users 仅 ADMIN）
    meta: { requiresAuth: true, layout: 'default', title: '用户管理', roles: ['ADMIN'] }
  },
  { path: '/', redirect: '/chat' },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：未登录访问受保护路由 -> /login；已登录访问 /login -> /chat；
// 角色守卫（M9/T4）：标记 meta.roles 的页面，当前用户角色需含其一，否则回首页（不渲染受保护页）。
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'chat' }
  }
  // 角色守卫
  if (to.meta.roles) {
    // 角色未加载（登录态但 roles 为空）时先懒加载一次身份快照
    if (!auth.roles || auth.roles.length === 0) {
      await auth.fetchMe()
    }
    const ok = (auth.roles || []).some((r) => to.meta.roles.includes(r))
    if (!ok) {
      return { name: 'chat' } // 无权限 → 回首页，不渲染受保护页
    }
  }
  return true
})

export default router
