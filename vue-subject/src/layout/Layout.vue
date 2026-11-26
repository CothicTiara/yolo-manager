<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="layout-aside">
      <div class="logo">
        <span v-if="!collapsed" class="logo-text">{{ $t('YOLO 管理平台') }}</span>
        <span v-else class="logo-mini">YOLO</span>
      </div>
      <el-menu
        :default-active="$route.path"
        :collapse="collapsed"
        router
        background-color="#001529"
        text-color="rgba(255,255,255,0.65)"
        active-text-color="#fff"
      >
        <el-menu-item index="/datasets">
          <el-icon><Folder /></el-icon>
          <template #title>{{ $t('数据集管理') }}</template>
        </el-menu-item>
        <el-menu-item index="/train">
          <el-icon><VideoPlay /></el-icon>
          <template #title>{{ $t('训练任务') }}</template>
        </el-menu-item>
        <el-menu-item index="/models">
          <el-icon><Cpu /></el-icon>
          <template #title>{{ $t('模型版本') }}</template>
        </el-menu-item>
        <el-menu-item index="/base-models">
          <el-icon><Files /></el-icon>
          <template #title>{{ $t('基础模型') }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="appStore.toggleSidebar()">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">{{ $t('首页') }}</el-breadcrumb-item>
            <el-breadcrumb-item v-if="$route.meta.title">{{ $t($route.meta.title) }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown trigger="click" @command="setLocale">
            <el-button size="small" plain>
              <el-icon style="margin-right:4px"><Operation /></el-icon>{{ currentLocaleLabel }}
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="l in LOCALES"
                  :key="l.value"
                  :command="l.value"
                  :disabled="l.value === locale"
                >{{ l.label }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button type="primary" plain size="small" @click="$router.push('/guide')">
            <el-icon style="margin-right:4px"><QuestionFilled /></el-icon>{{ $t('使用指南') }}
          </el-button>
          <el-tag type="success" effect="plain">ADMIN</el-tag>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useAppStore } from '@/store/app'
import { Folder, VideoPlay, Cpu, Expand, Fold, Files, QuestionFilled, Operation } from '@element-plus/icons-vue'
import { locale, setLocale, LOCALES } from '@/i18n'

const appStore = useAppStore()
const collapsed = computed(() => appStore.sidebarCollapsed)
const currentLocaleLabel = computed(() => (LOCALES.find((l) => l.value === locale.value) || LOCALES[0]).label)
</script>

<style scoped>
.layout {
  height: 100vh;
}
.layout-aside {
  background: #001529;
  transition: width 0.2s;
  overflow-x: hidden;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.logo-mini {
  font-size: 15px;
}
.layout-aside :deep(.el-menu) {
  border-right: none;
}
.layout-header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  height: 60px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}
.layout-main {
  padding: 16px;
  overflow: auto;
  background: #f5f7fa;
}
</style>