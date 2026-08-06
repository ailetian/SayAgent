<template>
  <div class="app-shell">
    <div class="ambient" />
    <el-container v-if="showChrome" class="app-chrome">
      <el-aside class="app-aside" width="232px">
        <div class="app-logo">
          <span class="logo-mark" />
          <span>Hify</span>
        </div>
        <nav class="app-nav">
          <router-link
            v-for="item in nav"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: activeMenu === item.path }"
          >
            <span class="nav-dot" />
            {{ item.label }}
          </router-link>
        </nav>
        <div class="app-foot">
          <div class="user-chip">
            <span class="user-avatar">{{ initial }}</span>
            <div class="user-meta">
              <div class="user-name">{{ auth.user?.username || '—' }}</div>
              <div class="user-role">{{ auth.user?.role || '' }}</div>
            </div>
          </div>
          <button class="btn-ghost logout-btn" @click="onLogout">退出</button>
        </div>
      </el-aside>

      <el-container>
        <el-header class="app-header">
          <span class="app-crumb">AI 员工制造厂</span>
          <span class="app-page">{{ currentTitle }}</span>
        </el-header>
        <el-main class="app-main">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
    <router-view v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 壳的显示只认路由：不是 login 就显示完整 chrome（侧边栏+顶栏）。
// 绝不依赖 auth.isLoggedIn —— 否则 token 过期/接口 401 触发 logout 时，
// isLoggedIn 变 false 会把壳瞬间卸掉（"刷新能看到、一闪就没"的根因）。
// 登录校验交给 router 守卫处理（未登录访问受保护页会跳 /login），与壳渲染解耦。
const showChrome = computed(() => route.name !== 'login')
const activeMenu = computed(() => '/' + (route.path.split('/')[1] || ''))
const currentTitle = computed(() => route.meta.title || 'Hify')
const initial = computed(() => (auth.user?.username || '?').charAt(0).toUpperCase())

const nav = [
  { path: '/chat', label: '对话' },
  { path: '/agents', label: 'Agent' },
  { path: '/knowledge', label: '知识库' },
  { path: '/models', label: '模型' },
  { path: '/mcp', label: 'MCP' }
]

function onLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style>
.app-shell { height: 100%; position: relative; }
.app-chrome { height: 100%; position: relative; z-index: 1; }

/* 侧边栏：玻璃拟态 */
.app-aside {
  background: var(--glass);
  border-right: 1px solid var(--line);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  display: flex; flex-direction: column;
  padding: 22px 16px;
  position: relative;
  z-index: 2;
}
.app-logo {
  display: flex; align-items: center; gap: 10px;
  font-size: 20px; font-weight: 800; letter-spacing: .5px;
  padding: 4px 8px 22px;
}
.logo-mark {
  width: 16px; height: 16px; border-radius: 50%;
  background: linear-gradient(120deg, var(--accent-a), var(--accent-b));
  box-shadow: 0 0 14px rgba(94, 234, 212, .5);
}
.app-nav { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 11px 14px; border-radius: 10px;
  color: var(--muted); text-decoration: none; font-size: 14px; font-weight: 600;
  transition: color .15s, background .15s;
}
.nav-item:hover { color: var(--text); background: var(--glass); }
.nav-item.active { color: var(--text); background: var(--glass-strong); }
.nav-dot {
  width: 6px; height: 6px; border-radius: 50%; background: currentColor; opacity: .5;
}
.nav-item.active .nav-dot { background: var(--accent-a); opacity: 1; box-shadow: 0 0 8px var(--accent-a); }

.app-foot { border-top: 1px solid var(--line); padding-top: 14px; }
.user-chip { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.user-avatar {
  width: 34px; height: 34px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(120deg, var(--accent-a), var(--accent-b));
  color: #14110F; font-weight: 800; font-size: 14px;
}
.user-name { font-size: 13px; font-weight: 600; }
.user-role { font-size: 11px; color: var(--muted); }
.logout-btn { width: 100%; justify-content: center; }

/* 顶栏 */
.app-header {
  display: flex; align-items: baseline; gap: 12px;
  height: 60px; padding: 0 30px;
  background: transparent; border-bottom: 1px solid var(--line);
}
.app-crumb { color: var(--muted); font-size: 13px; }
.app-page { font-size: 16px; font-weight: 700; }
.app-main { background: transparent; padding: 0; overflow: hidden; height: 100%; display: flex; flex-direction: column; }
.app-main > :deep(*) { flex: 1; min-height: 0; }
</style>
