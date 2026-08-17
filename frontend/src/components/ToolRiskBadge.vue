<template>
  <span class="trb">
    <span class="pill" :style="riskStyle">{{ riskLabel }}</span>
    <span class="pill" :style="sensStyle">{{ sensLabel }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

// 危险度 / 数据敏感度 → 中文标签 + 色值（§3.6 设计令牌）。
// 危险度：L0 青(#5EEAD4) / L1 蓝 / L2 琥珀(#FFB454) / L3 红(#FF7A6B)；
// 敏感度：PUBLIC 灰 / INTERNAL 蓝 / CONFIDENTIAL 琥珀(#FFB454) / FINANCE_HR 红(#FF7A6B)。
// 背景底统一用 #14110F 系（禁 #0D1117），此处用半透明色块浮于暗底。
const RISK = {
  L0_READONLY_SAFE:    { label: '只读安全', color: '#5EEAD4' },
  L1_WRITE_REVERSIBLE: { label: '写可逆',   color: '#6EA8FE' },
  L2_IRREVERSIBLE:     { label: '不可逆',   color: '#FFB454' },
  L3_HIGH_RISK:        { label: '高危',     color: '#FF7A6B' }
}
const SENS = {
  PUBLIC:       { label: '公开',     color: '#9CA3AF' },
  INTERNAL:     { label: '内部',     color: '#6EA8FE' },
  CONFIDENTIAL: { label: '机密',     color: '#FFB454' },
  FINANCE_HR:   { label: '财务·人事', color: '#FF7A6B' }
}

const props = defineProps({
  riskLevel: { type: String, default: 'L1_WRITE_REVERSIBLE' },
  dataSensitivity: { type: String, default: 'INTERNAL' }
})

const riskMeta = computed(() => RISK[props.riskLevel] || RISK.L1_WRITE_REVERSIBLE)
const sensMeta = computed(() => SENS[props.dataSensitivity] || SENS.INTERNAL)
const riskLabel = computed(() => riskMeta.value.label)
const sensLabel = computed(() => sensMeta.value.label)
const riskStyle = computed(() => styleFor(riskMeta.value.color))
const sensStyle = computed(() => styleFor(sensMeta.value.color))

function styleFor(c) {
  return { color: c, borderColor: c + '66', background: c + '1A' }
}
</script>

<style scoped>
.trb { display: inline-flex; gap: 6px; }
.pill {
  font-size: 11px; line-height: 1; padding: 3px 8px; border-radius: 999px;
  border: 1px solid; font-weight: 600; white-space: nowrap;
}
</style>
