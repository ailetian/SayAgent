<template>
  <div class="md" v-html="html" />
</template>

<script setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps({
  content: { type: String, default: '' }
})

// 代码块高亮由 theme.css 的 .md pre/code 着色；markdown-it 关闭 html 防注入。
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const html = computed(() => md.render(props.content || ''))
</script>
