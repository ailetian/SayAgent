<template>
  <div class="probe">
    <div class="field" style="max-width:720px">
      <input v-model="query" placeholder="输入问题，预览会命中哪些片段（不生成答案）" @keyup.enter="run" />
    </div>
    <div style="margin-top:12px">
      <button class="btn-grad" :disabled="!query || loading" @click="run">{{ loading ? '分析中…' : '试一试' }}</button>
    </div>

    <div v-if="r" class="probe-out">
      <div class="probe-status">
        <span class="tag" :class="r.hit ? 'tag-a' : 'tag-danger'">{{ r.hit ? '会命中（可答）' : '未命中（将拒答）' }}</span>
        <span class="muted" style="font-size:12px">
          topScore {{ fmt(r.topScore) }} / 阈值 {{ fmt(r.threshold) }}
        </span>
      </div>

      <div v-if="r.candidates && r.candidates.length" class="probe-cands">
        <div v-for="(c, i) in r.candidates" :key="i" class="glass pc">
          <div class="pc-head">
            <span class="tag tag-b">score {{ fmt(c.score) }}</span>
            <span class="muted" style="font-size:12px">doc {{ c.documentId }} · seq {{ c.seq }}</span>
          </div>
          <div class="md">{{ c.snippet }}</div>
        </div>
      </div>
      <div v-else class="muted">无候选片段。</div>
    </div>

    <p v-if="error" class="error-text" style="margin-top:12px">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { probeKb } from '../api/knowledge'

const props = defineProps({ kbId: { type: [Number, String], required: true } })
const query = ref('')
const r = ref(null)
const loading = ref(false)
const error = ref('')

function fmt(v) { return typeof v === 'number' ? v.toFixed(3) : (v ?? '—') }

async function run() {
  if (!query.value) return
  loading.value = true
  error.value = ''
  try {
    r.value = await probeKb(props.kbId, { query: query.value })
  } catch (e) {
    error.value = e.message || '试问失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.probe-status { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.probe-cands { margin-top: 14px; display: flex; flex-direction: column; gap: 12px; }
.pc { padding: 12px 14px; }
.pc-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
</style>
