<template>
  <div class="login-page">
    <!-- 运动诗学：全屏流体粒子流场背景（Field.io 式） -->
    <canvas ref="bgRef" class="login-bg"></canvas>

    <form class="login-card" @submit.prevent="onSubmit">
      <div class="brand">
        <svg class="mark" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <circle cx="16" cy="16" r="13" stroke="#5EEAD4" stroke-width="2" />
          <path d="M10 20 L16 9 L22 20" stroke="#FFB454" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span class="name">SayAgent</span>
      </div>
      <p class="tag">AI 员工制造厂 · 登录你的工作台</p>

      <label class="field-label" for="username">账号</label>
      <div class="field">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <circle cx="12" cy="8" r="4" />
          <path d="M4 20c0-4 4-6 8-6s8 2 8 6" />
        </svg>
        <input id="username" v-model="form.username" type="text" autocomplete="username" placeholder="请输入账号" :disabled="loading" />
      </div>

      <label class="field-label" for="password">密码</label>
      <div class="field">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <rect x="4" y="10" width="16" height="11" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
        </svg>
        <input id="password" v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码" :disabled="loading" @keyup.enter="onSubmit" />
      </div>

      <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>
      <p v-else class="login-error-spacer"></p>

      <button type="submit" class="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登 录' }}
      </button>

      <p class="hint">内部账号：admin / admin123</p>
    </form>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login as apiLogin } from '../api/auth'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorMsg = ref('')

// ---- 粒子流场背景（轻量、可取消）----
const bgRef = ref(null)
let rafId = 0
let particles = []

function initParticles(w, h) {
  particles = []
  for (let i = 0; i < 90; i++) {
    particles.push({
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.4,
      vy: (Math.random() - 0.5) * 0.4,
      r: Math.random() * 1.8 + 0.6,
      c: Math.random() > 0.5 ? '94,234,212' : '255,180,84'
    })
  }
}

function startCanvas() {
  const canvas = bgRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  const resize = () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
    initParticles(canvas.width, canvas.height)
  }
  resize()
  window.addEventListener('resize', resize)

  const tick = () => {
    const w = canvas.width
    const h = canvas.height
    ctx.clearRect(0, 0, w, h)
    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > w) p.vx *= -1
      if (p.y < 0 || p.y > h) p.vy *= -1
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(${p.c},0.7)`
      ctx.fill()
    }
    rafId = requestAnimationFrame(tick)
  }
  tick()

  onUnmounted(() => {
    cancelAnimationFrame(rafId)
    window.removeEventListener('resize', resize)
  })
}

onMounted(startCanvas)

async function onSubmit() {
  errorMsg.value = ''
  if (!form.username || !form.password) {
    errorMsg.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    // 走 F1 统一 request 封装：自动带 Authorization、解析 {code,data,message}
    const data = await apiLogin(form.username, form.password)
    // 成功：仅写入 token + 用户信息（绝不缓存/打印 password）
    auth.login(data.token, { username: data.username, role: data.role })
    // 仅允许同源内部路径，防止 open-redirect 跳转到外部/绝对地址
    const raw = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    const redirect =
      raw.startsWith('/') && !raw.startsWith('//') && !raw.includes('://') ? raw : '/chat'
    router.push(redirect)
  } catch (e) {
    // 失败：仅红字提示，不写入 token
    errorMsg.value = e?.message || '登录失败，请检查账号密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #14110f;
  color-scheme: dark; /* 让原生表单控件（含 autofill）走暗色渲染 */
  font-family: 'Inter', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  color: #f5f5f0;
  overflow: hidden;
}
.login-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
}
.login-card {
  position: relative;
  z-index: 1;
  width: 380px;
  max-width: calc(100vw - 32px);
  padding: 40px 36px;
  border-radius: 20px;
  background: rgba(20, 17, 15, 0.55);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border: 1px solid rgba(245, 245, 240, 0.12);
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.45);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.mark {
  width: 34px;
  height: 34px;
}
.name {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.tag {
  color: #a8a29e;
  font-size: 13px;
  margin: 10px 0 28px;
}
.field-label {
  display: block;
  font-size: 12px;
  color: #a8a29e;
  margin: 14px 0 6px;
  letter-spacing: 0.4px;
}
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(0, 0, 0, 0.28);
  border: 1px solid rgba(245, 245, 240, 0.14);
  border-radius: 12px;
  padding: 0 14px;
  transition: border-color 0.15s;
}
.field:focus-within {
  border-color: #5eead4;
}
.field svg {
  width: 18px;
  height: 18px;
  color: #a8a29e;
  flex: 0 0 auto;
}
.field input {
  flex: 1;
  background: transparent;
  border: 0;
  outline: 0;
  color: #f5f5f0;
  font-size: 15px;
  padding: 13px 0;
}
/* 覆盖浏览器自动填充(autofill)的白色背景，保持沉浸式暗色 */
.field input:-webkit-autofill,
.field input:-webkit-autofill:hover,
.field input:-webkit-autofill:focus {
  -webkit-text-fill-color: #f5f5f0;
  -webkit-box-shadow: 0 0 0 1000px rgba(0, 0, 0, 0.28) inset;
  caret-color: #f5f5f0;
  transition: background-color 9999s ease-in-out 0s;
}
.field input::placeholder {
  color: #6b6661;
}
.login-error {
  color: #ff7a6b;
  font-size: 13px;
  min-height: 18px;
  margin-top: 12px;
}
.login-error-spacer {
  min-height: 18px;
  margin-top: 12px;
}
.submit {
  width: 100%;
  margin-top: 10px;
  padding: 14px;
  border: 0;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  color: #14110f;
  cursor: pointer;
  background: linear-gradient(100deg, #5eead4, #ffb454);
  transition: transform 0.15s, filter 0.15s;
}
.submit:hover:not(:disabled) {
  filter: brightness(1.05);
  transform: translateY(-1px);
}
.submit:disabled {
  opacity: 0.7;
  cursor: default;
}
.hint {
  margin-top: 18px;
  font-size: 12px;
  color: #a8a29e;
  text-align: center;
}
</style>
