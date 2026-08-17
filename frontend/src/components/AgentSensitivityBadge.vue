<template>
  <span class="asb" :style="badge.style">{{ badge.text }}</span>
</template>

<script setup>
import { computed } from 'vue'

// Agent 聚合敏感度徽章（M10/T6，§2.1 授权知情）。
// 数据来自后端 AgentVO 聚合字段（financeHrToolCount / confidentialToolCount / maxRiskLevel），前端不自行计算（§3.2）。
const props = defineProps({
  maxDataSensitivity: { type: String, default: 'INTERNAL' },
  maxRiskLevel: { type: String, default: 'L0_READONLY_SAFE' },
  financeHrToolCount: { type: Number, default: 0 },
  confidentialToolCount: { type: Number, default: 0 }
})

const badge = computed(() => {
  if (props.financeHrToolCount > 0) {
    return { text: `含财务·人事域工具 ×${props.financeHrToolCount}`, style: styleFor('#FF7A6B') }
  }
  if (props.confidentialToolCount > 0) {
    return { text: `含机密域工具 ×${props.confidentialToolCount}`, style: styleFor('#FFB454') }
  }
  if (props.maxRiskLevel === 'L2_IRREVERSIBLE' || props.maxRiskLevel === 'L3_HIGH_RISK') {
    const lvl = props.maxRiskLevel === 'L3_HIGH_RISK' ? 'L3' : 'L2'
    return { text: `最高危险度 ${lvl}`, style: styleFor('#FFB454') }
  }
  // 常规：用静音色，保持列视觉连续（不喧宾夺主）
  return { text: '常规', style: styleFor('#5EEAD4') }
})

function styleFor(c) {
  return { color: c, borderColor: c + '66', background: c + '1A' }
}
</script>

<style scoped>
.asb {
  display: inline-block; font-size: 11px; line-height: 1;
  padding: 3px 8px; border-radius: 999px; border: 1px solid; font-weight: 600;
}
</style>
