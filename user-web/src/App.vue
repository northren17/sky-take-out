<script setup lang="ts">
import { useRoute } from 'vue-router'
import MainLayout from '@/components/MainLayout.vue'

const route = useRoute()
</script>

<template>
  <!-- 整页铺满视口：不再限制成小程序那样的窄容器，各页面用 .sky-container 控制版心 -->
  <div class="app-frame">
    <MainLayout v-if="route.meta.layout !== 'none'">
      <RouterView />
    </MainLayout>
    <RouterView v-else />
  </div>
</template>

<style scoped>
.app-frame {
  position: relative;
  width: 100%;
  min-height: 100vh;
  background: var(--sky-surface-2);
  overflow-x: hidden;
}
</style>
