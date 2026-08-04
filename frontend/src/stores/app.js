import { defineStore } from 'pinia'
import { ref } from 'vue'

// 全局 UI 状态（可选复用）：侧边栏折叠等。
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return { sidebarCollapsed, toggleSidebar }
})
