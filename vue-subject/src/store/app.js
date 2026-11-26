import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 全局 UI 状态 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const activeDatasetId = ref(null)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return { sidebarCollapsed, activeDatasetId, toggleSidebar }
})