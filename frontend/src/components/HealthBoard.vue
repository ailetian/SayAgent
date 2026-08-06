<template>
  <div class="health">
    <div v-if="loading" class="muted">体检中…</div>
    <template v-else-if="h">
      <div class="health-cards">
        <div class="glass hc">
          <div class="hc-label">基础健康</div>
          <div class="hc-big" :class="healthClass(h.basicHealth)">{{ h.basicHealth }}</div>
          <div class="hc-sub">健康分 {{ pct(h.healthScore) }} · 已索引 {{ h.docIndexed }}/{{ h.docTotal }}</div>
        </div>
        <div class="glass hc">
          <div class="hc-label">命中质量</div>
          <div class="hc-big">{{ pct(h.hitQuality) }}</div>
          <div class="hc-sub">近 50 次检索平均最高余弦分</div>
        </div>
        <div class="glass hc">
          <div class="hc-label">响应速度</div>
          <div class="hc-big">{{ Math.round(h.responseSpeedMs) }}<span class="hc-unit">ms</span></div>
          <div class="hc-sub">近 50 次平均耗时 · 拒答率 {{ pct(h.refusalRate) }}</div>
        </div>
      </div>

      <div class="glass health-detail">
        <div class="hd-row"><span>文档总数</span><b>{{ h.docTotal }}</b></div>
        <div class="hd-row"><span>已索引</span><b class="ok">{{ h.docIndexed }}</b></div>
        <div class="hd-row"><span>索引失败</span><b class="bad">{{ h.docFailed }}</b></div>
        <div class="hd-row"><span>检索次数</span><b>{{ h.retrievalCount }}</b></div>
      </div>

      <div class="muted hc-note">
        说明：当前后端 HealthVO 提供「基础健康 / 命中质量 / 响应速度」三指标；「最近被拒 TOP-N」需接 retrieval_log，
        后端暂未返回（待 K10 评测补齐）。
      </div>
    </template>
    <p v-if="error" class="error-text">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { healthKb } from '../api/knowledge'

const props = defineProps({ kbId: { type: [Number, String], required: true } })
const h = ref(null)
const loading = ref(false)
const error = ref('')

function pct(v) { return Math.round((v || 0) * 100) + '%' }
function healthClass(b) {
  if (b === 'HEALTHY') return 'ok'
  if (b === 'DEGRADED') return 'warn'
  return 'bad'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    h.value = await healthKb(props.kbId)
  } catch (e) {
    error.value = e.message || '体检失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.health-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.hc { padding: 18px 20px; }
.hc-label { color: var(--muted); font-size: 13px; }
.hc-big { font-size: 26px; font-weight: 800; margin: 10px 0 6px; letter-spacing: .5px; }
.hc-big.ok { color: var(--accent-a); }
.hc-big.warn { color: var(--accent-b); }
.hc-big.bad { color: var(--danger); }
.hc-unit { font-size: 14px; font-weight: 600; margin-left: 2px; }
.hc-sub { color: var(--muted); font-size: 12px; }
.health-detail { margin-top: 16px; padding: 8px 18px; }
.hd-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid var(--line); font-size: 14px; }
.hd-row:last-child { border-bottom: 0; }
.hd-row .ok { color: var(--accent-a); }
.hd-row .bad { color: var(--danger); }
.hc-note { font-size: 12px; margin-top: 14px; line-height: 1.6; }
@media (max-width: 760px) { .health-cards { grid-template-columns: 1fr; } }
</style>
